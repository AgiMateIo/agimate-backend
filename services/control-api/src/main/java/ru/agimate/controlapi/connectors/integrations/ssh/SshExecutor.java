package ru.agimate.controlapi.connectors.integrations.ssh;

import org.apache.sshd.client.ClientBuilder;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.kex.BuiltinDHFactories;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.apache.sshd.common.signature.Signature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.agimate.common.net.PublicTargets;
import ru.agimate.common.net.TargetNotAllowedException;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * The only network code of the SSH connector. One call means one fresh MINA client, one session and
 * at most one command: no pool, no PTY, no shell. The call is bounded twice — sixteen in flight on
 * the node and two on one connection — and both permits are taken before DNS, so a busy call waits
 * for nothing. A target refused by {@link PublicTargets} never opens a socket and never reaches the
 * host-key or authentication stage.
 *
 * <p>Only an explicit TCP setup failure may move to the next validated address of the same lookup,
 * and only while the host key has not been checked yet. A host key mismatch, an authentication
 * failure, a channel failure or anything after the exec request was sent ends the call: the command
 * may have run, so retrying is not ours to decide.
 */
@Component
public class SshExecutor {

    static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(10);
    static final int NODE_PERMITS = 16;
    static final int CONNECTION_PERMITS = 2;

    private static final String BUSY_NODE = "SSH executor is busy, try again later";
    private static final String BUSY_CONNECTION = "Too many concurrent SSH commands for this connection";
    private static final String UNRESOLVED_HOST = "Unable to resolve the SSH host";
    private static final String REFUSED_TARGET = "Connection to this SSH host is not allowed";
    private static final String CONNECT_FAILED = "Unable to establish an SSH connection";
    static final String HOST_KEY_MISMATCH = "SSH host key does not match the pinned fingerprint";
    static final String AUTHENTICATION_FAILED = "SSH authentication failed";
    private static final String COMMAND_NOT_STARTED = "Unable to start the SSH command";
    private static final String UNKNOWN_OUTCOME = "The SSH command outcome is unknown after a connection failure";

    /**
     * The wire names of the SHA-1 algorithms: {@code ssh-rsa}/{@code ssh-dss} and their certificate
     * forms. Exclusion is by name so the deprecated enum constants are never referenced.
     */
    private static final Set<String> LEGACY_SIGNATURE_NAMES = Set.of(
            "ssh-rsa", "ssh-rsa-cert-v01@openssh.com",
            "ssh-dss", "ssh-dss-cert-v01@openssh.com");

    private static final List<NamedFactory<Signature>> SIGNATURE_FACTORIES = modernSignatures();
    private static final List<KeyExchangeFactory> KEY_EXCHANGE_FACTORIES = modernKeyExchanges();

    private final Supplier<SshClient> clientFactory;
    private final PublicTargets targets;
    private final Semaphore nodePermits = new Semaphore(NODE_PERMITS);
    // Deliberately permanent: eviction could hand out a second semaphore while the old one still
    // holds permits, breaking the two-per-connection cap. Entries are bounded by the connection ids
    // actually used, a few dozen bytes each, so never evicting is cheap.
    private final ConcurrentMap<String, Semaphore> connectionPermits = new ConcurrentHashMap<>();

    @Autowired
    public SshExecutor(@Value("${app.net.allow-private-targets:false}") boolean allowPrivateTargets) {
        this(SshClient::setUpDefaultClient, new PublicTargets(allowPrivateTargets));
    }

    SshExecutor(Supplier<SshClient> clientFactory, PublicTargets targets) {
        this.clientFactory = clientFactory;
        this.targets = targets;
    }

    /** Handshake and authentication only: proves the credentials and the pinned key reach a shell. */
    public void validate(SshConnectionConfig config) {
        acquireNodePermit();
        try {
            openSession(config).close();
        } finally {
            nodePermits.release();
        }
    }

    public SshCommandResult execute(String connectionId, SshConnectionConfig config, SshCommand command) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new ConnectorException("SSH connection is not available");
        }
        acquireNodePermit();
        try {
            Semaphore permits = connectionPermits.computeIfAbsent(connectionId,
                    id -> new Semaphore(CONNECTION_PERMITS));
            if (!permits.tryAcquire()) {
                throw new ConnectorException(BUSY_CONNECTION);
            }
            try {
                return runCommand(config, command);
            } finally {
                permits.release();
            }
        } finally {
            nodePermits.release();
        }
    }

    private void acquireNodePermit() {
        if (!nodePermits.tryAcquire()) {
            throw new ConnectorException(BUSY_NODE);
        }
    }

    private SshCommandResult runCommand(SshConnectionConfig config, SshCommand command) {
        OpenedSession opened = openSession(config);
        try {
            return runCommand(opened, command);
        } finally {
            opened.close();
        }
    }

    private SshCommandResult runCommand(OpenedSession opened, SshCommand command) {
        ChannelExec channel;
        try {
            channel = opened.session().createExecChannel(command.command());
        } catch (IOException | RuntimeException e) {
            throw new ConnectorException(COMMAND_NOT_STARTED);
        }
        if (channel == null) {
            throw new ConnectorException(COMMAND_NOT_STARTED);
        }
        SshOutputCollector stdout = new SshOutputCollector();
        SshOutputCollector stderr = new SshOutputCollector();
        try {
            // Both streams must be installed before the request is sent, or MINA buffers and the
            // early bytes of a fast command are lost.
            channel.setOut(stdout);
            channel.setErr(stderr);
        } catch (RuntimeException e) {
            closeChannel(channel);
            throw new ConnectorException(COMMAND_NOT_STARTED);
        }
        try {
            channel.open().verify(HANDSHAKE_TIMEOUT);
            long startedAt = System.nanoTime();
            Set<ClientChannelEvent> events =
                    channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), command.timeout());
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            boolean timedOut = events != null && events.contains(ClientChannelEvent.TIMEOUT);
            Integer exitCode = timedOut ? null : channel.getExitStatus();
            return new SshCommandResult(stdout.text(), stderr.text(), exitCode, timedOut,
                    stdout.truncated() || stderr.truncated(), durationMs);
        } catch (IOException | RuntimeException e) {
            // The exec request has left the process: the remote command may have run, so no retry.
            throw new ConnectorException(UNKNOWN_OUTCOME);
        } finally {
            closeChannel(channel);
        }
    }

    private OpenedSession openSession(SshConnectionConfig config) {
        // Parsing before resolving also keeps a bad key from costing a network round trip.
        KeyPair keyPair = config.privateKey() == null ? null
                : SshKeyLoader.loadKeyPair(config.privateKey(), config.privateKeyPassphrase());
        List<InetSocketAddress> endpoints = endpointAddresses(config);
        SshHostKeyVerifier verifier = new SshHostKeyVerifier(config.hostKeyFingerprint());
        for (InetSocketAddress endpoint : endpoints) {
            Attempt attempt = attemptConnect(config, keyPair, endpoint, verifier);
            if (attempt != null) {
                return new OpenedSession(attempt.client(), attempt.session());
            }
        }
        throw new ConnectorException(CONNECT_FAILED);
    }

    /**
     * Resolves once and hands MINA a numeric address, so the only host it ever connects to is one
     * {@link PublicTargets} has already vetted — a name cannot answer differently to a second
     * lookup performed inside the library.
     */
    private List<InetSocketAddress> endpointAddresses(SshConnectionConfig config) {
        InetAddress[] resolved;
        try {
            resolved = targets.resolve(config.host());
        } catch (UnknownHostException e) {
            throw new ConnectorException(UNRESOLVED_HOST);
        } catch (TargetNotAllowedException e) {
            throw new ConnectorException(REFUSED_TARGET);
        }
        if (resolved == null || resolved.length == 0) {
            throw new ConnectorException(UNRESOLVED_HOST);
        }
        List<InetSocketAddress> endpoints = new ArrayList<>(resolved.length);
        for (InetAddress address : resolved) {
            try {
                targets.requirePublic(address);
            } catch (TargetNotAllowedException e) {
                throw new ConnectorException(REFUSED_TARGET);
            }
            try {
                endpoints.add(new InetSocketAddress(
                        InetAddress.getByAddress(address.getAddress()), config.port()));
            } catch (UnknownHostException e) {
                throw new ConnectorException(UNRESOLVED_HOST);
            }
        }
        return endpoints;
    }

    /**
     * @return the open session, or {@code null} when the TCP setup itself failed and no host key
     *         has been checked yet, so the next validated address may be tried
     */
    private Attempt attemptConnect(SshConnectionConfig config, KeyPair keyPair,
                                   InetSocketAddress endpoint, SshHostKeyVerifier verifier) {
        AtomicBoolean hostKeyChecked = new AtomicBoolean();
        ServerKeyVerifier tracking = (session, remoteAddress, serverKey) -> {
            hostKeyChecked.set(true);
            return verifier.verifyServerKey(session, remoteAddress, serverKey);
        };
        SshClient client = createClient(config, tracking);
        boolean keep = false;
        try {
            try {
                client.start();
            } catch (RuntimeException e) {
                throw new ConnectorException(CONNECT_FAILED);
            }
            ClientSession session;
            try {
                ConnectFuture future = client.connect(config.username(), endpoint, null, null);
                session = future.verify(HANDSHAKE_TIMEOUT).getSession();
            } catch (IOException | RuntimeException e) {
                if (isTcpFailure(e) && !hostKeyChecked.get()) {
                    return null;
                }
                throw connectFailure(verifier);
            }
            if (session == null) {
                throw connectFailure(verifier);
            }
            try {
                if (keyPair == null) {
                    session.addPasswordIdentity(config.password());
                } else {
                    session.addPublicKeyIdentity(keyPair);
                }
                session.auth().verify(HANDSHAKE_TIMEOUT);
            } catch (IOException | RuntimeException e) {
                closeSession(session);
                throw authenticationFailure(verifier);
            }
            keep = true;
            return new Attempt(client, session);
        } finally {
            if (!keep) {
                stopClient(client);
            }
        }
    }

    private SshClient createClient(SshConnectionConfig config, ServerKeyVerifier verifier) {
        SshClient client = clientFactory.get();
        if (client == null) {
            throw new ConnectorException(CONNECT_FAILED);
        }
        // setUpDefaultClient() reads ~/.ssh/config and default identities unless told otherwise;
        // the connection carries its own credentials, so the machine's must never take part.
        client.setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
        client.setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
        client.setUserInteraction(UserInteraction.NONE);
        client.setAgentFactory(null);
        client.setSignatureFactories(SIGNATURE_FACTORIES);
        client.setKeyExchangeFactories(KEY_EXCHANGE_FACTORIES);
        client.setServerKeyVerifier(verifier);
        client.setUserAuthFactories(List.of(authFactory(config)));
        return client;
    }

    private static UserAuthFactory authFactory(SshConnectionConfig config) {
        return config.password() != null ? new UserAuthPasswordFactory() : new UserAuthPublicKeyFactory();
    }

    private static List<NamedFactory<Signature>> modernSignatures() {
        List<NamedFactory<Signature>> factories = new ArrayList<>();
        for (BuiltinSignatures factory : BuiltinSignatures.VALUES) {
            if (!LEGACY_SIGNATURE_NAMES.contains(factory.getName()) && factory.isSupported()) {
                factories.add(factory);
            }
        }
        return List.copyOf(factories);
    }

    private static List<KeyExchangeFactory> modernKeyExchanges() {
        List<KeyExchangeFactory> factories = new ArrayList<>();
        for (BuiltinDHFactories factory : BuiltinDHFactories.VALUES) {
            if (factory.isSupported() && !factory.getName().endsWith("-sha1")) {
                factories.add(ClientBuilder.DH2KEX.apply(factory));
            }
        }
        return List.copyOf(factories);
    }

    private static boolean isTcpFailure(Throwable failure) {
        // A visited set rather than plain traversal: a malformed cause chain can loop back on itself.
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof ConnectException || current instanceof NoRouteToHostException) {
                return true;
            }
        }
        return false;
    }

    private static ConnectorException connectFailure(SshHostKeyVerifier verifier) {
        return verifier.lastMismatch() == null
                ? new ConnectorException(CONNECT_FAILED)
                : hostKeyMismatch(verifier);
    }

    private static ConnectorException authenticationFailure(SshHostKeyVerifier verifier) {
        return verifier.lastMismatch() == null
                ? new ConnectorException(AUTHENTICATION_FAILED)
                : hostKeyMismatch(verifier);
    }

    private static ConnectorException hostKeyMismatch(SshHostKeyVerifier verifier) {
        return new ConnectorException(HOST_KEY_MISMATCH + " (" + verifier.lastMismatch() + ")");
    }

    private static void closeSession(ClientSession session) {
        try {
            session.close();
        } catch (IOException | RuntimeException ignored) {
            // The session is already failing; the caller reports the safe outcome.
        }
    }

    private static void closeChannel(ChannelExec channel) {
        try {
            channel.close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: a channel that cannot close does not change the reported outcome.
        }
    }

    private static void stopClient(SshClient client) {
        try {
            client.stop();
        } catch (RuntimeException ignored) {
            // An IO processor of a failed attempt cannot alter the outcome.
        }
    }

    private record Attempt(SshClient client, ClientSession session) {
    }

    private record OpenedSession(SshClient client, ClientSession session) {

        void close() {
            closeSession(session);
            stopClient(client);
        }
    }
}
