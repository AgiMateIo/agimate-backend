package ru.agimate.controlapi.connectors.integrations.ssh;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.keyboard.UserInteraction;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.config.hosts.HostConfigEntryResolver;
import org.apache.sshd.client.future.AuthFuture;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.kex.KeyExchangeFactory;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.Signature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.common.net.EgressProxy;
import ru.agimate.common.net.PublicTargets;
import ru.agimate.common.net.TargetNotAllowedException;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SshExecutor")
class SshExecutorTest {

    private static final String ED25519_FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
    private static final String RSA_FINGERPRINT = "SHA256:HWWZq7WaZg2J/vjQggH4YuEAUVFyEwmNzQYNmZXyzv8";
    private static final String TEST_PASSPHRASE = "agimate-test-passphrase";
    private static final String COMMAND = "uptime";
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);

    // ---- fixtures ---------------------------------------------------------

    private static SshConnectionConfig passwordConfig() {
        return new SshConnectionConfig("example.com", 2222, "deploy", ED25519_FINGERPRINT, null, null, "s3cret");
    }

    private static SshConnectionConfig keyConfig() throws IOException {
        return new SshConnectionConfig("example.com", 2222, "deploy", ED25519_FINGERPRINT,
                fixture("/ssh/id_ed25519_encrypted"), TEST_PASSPHRASE, null);
    }

    private static SshCommand command() {
        return new SshCommand(COMMAND, COMMAND_TIMEOUT);
    }

    private static InetAddress publicAddress() throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{1, 2, 3, 4});
    }

    private static InetAddress otherPublicAddress() throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{1, 2, 3, 5});
    }

    private static PublicTargets publicTargets(InetAddress... addresses) throws UnknownHostException {
        PublicTargets targets = mock(PublicTargets.class);
        lenient().when(targets.resolve(anyString())).thenReturn(addresses);
        lenient().when(targets.allowsPrivate()).thenReturn(false);
        return targets;
    }

    private static String fixture(String resource) throws IOException {
        try (InputStream in = SshExecutorTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Missing test fixture " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static PublicKey publicKey(String resource) throws Exception {
        List<AuthorizedKeyEntry> entries =
                AuthorizedKeyEntry.readAuthorizedKeys(new StringReader(fixture(resource)), false);
        return entries.getFirst().resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
    }

    // ---- MINA stub --------------------------------------------------------

    /** One mocked MINA client with a successful-by-default handshake and an optional exec channel. */
    private static final class ClientStub {

        final SshClient client = mock(SshClient.class);
        final ConnectFuture connectFuture = mock(ConnectFuture.class);
        final ClientSession session = mock(ClientSession.class);
        final AuthFuture authFuture = mock(AuthFuture.class);
        OpenFuture openFuture;
        ChannelExec channel;

        ClientStub() throws IOException {
            lenient().when(connectFuture.verify(any(Duration.class))).thenReturn(connectFuture);
            lenient().when(connectFuture.getSession()).thenReturn(session);
            lenient().when(session.auth()).thenReturn(authFuture);
            lenient().when(authFuture.verify(any(Duration.class))).thenReturn(authFuture);
            lenient().when(client.connect(anyString(), any(SocketAddress.class), any(), any()))
                    .thenReturn(connectFuture);
        }

        ClientStub servingCommand(Integer exitCode, Set<ClientChannelEvent> events) throws IOException {
            channel = mock(ChannelExec.class);
            openFuture = mock(OpenFuture.class);
            lenient().when(session.createExecChannel(COMMAND)).thenReturn(channel);
            lenient().when(channel.open()).thenReturn(openFuture);
            lenient().when(openFuture.verify(any(Duration.class))).thenReturn(openFuture);
            lenient().when(channel.waitFor(any(), any(Duration.class))).thenReturn(events);
            lenient().when(channel.getExitStatus()).thenReturn(exitCode);
            return this;
        }

        void failAuthentication(IOException failure) throws IOException {
            doThrow(failure).when(authFuture).verify(any(Duration.class));
        }

        void failOpen(IOException failure) throws IOException {
            doThrow(failure).when(openFuture).verify(any(Duration.class));
        }
    }

    /** MINA feeds both streams from its IO thread while {@code waitFor} blocks; this mimics that. */
    private static void streamDuringWait(ChannelExec channel, byte[] out, byte[] err,
                                         Set<ClientChannelEvent> events) {
        AtomicReference<OutputStream> stdout = new AtomicReference<>();
        AtomicReference<OutputStream> stderr = new AtomicReference<>();
        doAnswer(invocation -> {
            stdout.set(invocation.getArgument(0));
            return null;
        }).when(channel).setOut(any(OutputStream.class));
        doAnswer(invocation -> {
            stderr.set(invocation.getArgument(0));
            return null;
        }).when(channel).setErr(any(OutputStream.class));
        doAnswer(invocation -> {
            stdout.get().write(out);
            stderr.get().write(err);
            return events;
        }).when(channel).waitFor(any(), any(Duration.class));
    }

    private static ClientStub blockingClient(CountDownLatch entered, CountDownLatch release, Integer exitCode)
            throws IOException {
        ClientStub stub = new ClientStub().servingCommand(exitCode, EnumSet.of(ClientChannelEvent.CLOSED));
        doAnswer(invocation -> {
            entered.countDown();
            release.await(10, TimeUnit.SECONDS);
            return stub.connectFuture;
        }).when(stub.client).connect(anyString(), any(SocketAddress.class), any(), any());
        return stub;
    }

    private static SshExecutor executor(Supplier<SshClient> supplier, PublicTargets targets) {
        return new SshExecutor(supplier, targets);
    }

    private static void assertNoPermitLeak(String message) {
        String lower = message.toLowerCase();
        assertFalse(lower.contains("busy"), message);
        assertFalse(lower.contains("concurrent"), message);
    }

    // ---- address handling -------------------------------------------------

    @Nested
    @DisplayName("адреса и сеть")
    class Addresses {

        @Test
        @DisplayName("в MINA уходит только числовой адрес: повторного резолва имени нет")
        void resolvedAddressCannotBeResolvedAgain() throws Exception {
            InetAddress rebound = InetAddress.getByAddress("rebind.example", new byte[]{1, 2, 3, 4});
            PublicTargets targets = publicTargets(rebound);
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));

            executor(() -> stub.client, targets).execute("conn", passwordConfig(), command());

            var captor = org.mockito.ArgumentCaptor.forClass(SocketAddress.class);
            verify(stub.client).connect(anyString(), captor.capture(), isNull(), isNull());
            InetSocketAddress socket = assertInstanceOf(InetSocketAddress.class, captor.getValue());
            assertArrayEquals(new byte[]{1, 2, 3, 4}, socket.getAddress().getAddress());
            assertEquals("1.2.3.4", socket.getHostString());
            assertEquals(2222, socket.getPort());
            verify(targets, times(1)).resolve("example.com");
        }

        @Test
        @DisplayName("приватный адрес отклоняется до создания MINA-клиента")
        void privateTargetIsRefusedBeforeClientCreation() throws Exception {
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            PublicTargets targets = mock(PublicTargets.class);
            when(targets.resolve("example.com")).thenReturn(new InetAddress[]{loopback});
            doThrow(new TargetNotAllowedException("not allowed")).when(targets).requirePublic(loopback);
            AtomicInteger clientsCreated = new AtomicInteger();

            SshExecutor executor = executor(() -> {
                clientsCreated.incrementAndGet();
                return mock(SshClient.class);
            }, targets);

            ConnectorException failure = assertThrows(ConnectorException.class,
                    () -> executor.execute("conn", passwordConfig(), command()));

            assertTrue(failure.getMessage().contains("not allowed"));
            assertEquals(0, clientsCreated.get());
        }

        @Test
        @DisplayName("при allow-private-targets=true loopback разрешён")
        void loopbackIsAllowedWithPrivateTargets() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));
            SshConnectionConfig config = new SshConnectionConfig(
                    "127.0.0.1", 2222, "deploy", ED25519_FINGERPRINT, null, null, "s3cret");

            SshCommandResult result = executor(() -> stub.client, new PublicTargets(true))
                    .execute("conn", config, command());

            assertEquals(0, result.exitCode());
        }

        @Test
        @DisplayName("ошибка TCP на первом адресе переходит ко второму, exec выполняется один раз")
        void tcpFailureFallsBackToNextValidatedAddress() throws Exception {
            PublicTargets targets = publicTargets(publicAddress(), otherPublicAddress());
            ClientStub unreachable = new ClientStub();
            when(unreachable.client.connect(anyString(), any(SocketAddress.class), any(), any()))
                    .thenThrow(new ConnectException("refused"));
            ClientStub reachable = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));
            List<ClientStub> stubs = List.of(unreachable, reachable);
            AtomicInteger next = new AtomicInteger();

            SshCommandResult result = executor(() -> stubs.get(next.getAndIncrement()).client, targets)
                    .execute("conn", passwordConfig(), command());

            assertEquals(0, result.exitCode());
            assertEquals(2, next.get());
            verify(unreachable.client).start();
            verify(unreachable.client).stop();
            verify(unreachable.connectFuture, never()).verify(any(Duration.class));
            verify(reachable.session, times(1)).createExecChannel(COMMAND);
            verify(targets, times(1)).resolve("example.com");
        }

        @Test
        @DisplayName("несовпадение host key не перебирает следующие адреса")
        void hostKeyMismatchDoesNotFallBack() throws Exception {
            PublicTargets targets = publicTargets(publicAddress(), otherPublicAddress());
            ClientStub stub = new ClientStub();
            AtomicReference<ServerKeyVerifier> verifier = new AtomicReference<>();
            doAnswer(invocation -> {
                verifier.set(invocation.getArgument(0));
                return null;
            }).when(stub.client).setServerKeyVerifier(any());
            when(stub.client.connect(anyString(), any(SocketAddress.class), any(), any()))
                    .thenAnswer(invocation -> {
                        // MINA verifies the server key during the handshake, then the transport fails.
                        assertFalse(verifier.get().verifyServerKey(null, null, publicKey("/ssh/id_rsa_encrypted.pub")));
                        throw new ConnectException("refused");
                    });
            AtomicInteger clientsCreated = new AtomicInteger();

            ConnectorException failure = assertThrows(ConnectorException.class,
                    () -> executor(() -> {
                        clientsCreated.incrementAndGet();
                        return stub.client;
                    }, targets).execute("conn", passwordConfig(), command()));

            assertEquals(1, clientsCreated.get());
            assertTrue(failure.getMessage().contains("does not match"), failure.getMessage());
            assertTrue(failure.getMessage().contains(ED25519_FINGERPRINT), failure.getMessage());
            assertTrue(failure.getMessage().contains(RSA_FINGERPRINT), failure.getMessage());
            verify(stub.session, never()).addPasswordIdentity(anyString());
            verify(stub.session, never()).auth();
        }

        @Test
        @DisplayName("таймаут TCP не повторяется на следующем адресе")
        void socketTimeoutDoesNotRetry() throws Exception {
            PublicTargets targets = publicTargets(publicAddress(), otherPublicAddress());
            ClientStub stub = new ClientStub();
            doThrow(new SocketTimeoutException("handshake stalled"))
                    .when(stub.connectFuture).verify(any(Duration.class));
            AtomicInteger clientsCreated = new AtomicInteger();

            ConnectorException failure = assertThrows(ConnectorException.class,
                    () -> executor(() -> {
                        clientsCreated.incrementAndGet();
                        return stub.client;
                    }, targets).execute("conn", passwordConfig(), command()));

            assertEquals(1, clientsCreated.get());
            verify(stub.client, times(1)).connect(anyString(), any(SocketAddress.class), any(), any());
            verify(stub.client).stop();
            verify(stub.session, never()).auth();
            assertFalse(failure.getMessage().contains("stalled"), failure.getMessage());
        }
    }

    // ---- handshake and authentication -------------------------------------

    @Nested
    @DisplayName("рукопожатие и аутентификация")
    class Handshake {

        @Test
        @DisplayName("connect, auth и open ждут по 10 секунд")
        void handshakeUsesTenSecondTimeouts() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));

            executor(() -> stub.client, publicTargets(publicAddress())).execute("conn", passwordConfig(), command());

            verify(stub.connectFuture).verify(Duration.ofSeconds(10));
            verify(stub.authFuture).verify(Duration.ofSeconds(10));
            verify(stub.openFuture).verify(Duration.ofSeconds(10));
            verify(stub.channel).waitFor(any(), eq(COMMAND_TIMEOUT));
        }

        @Test
        @DisplayName("credentials добавляются только после завершения проверки хоста")
        void credentialsAreAddedOnlyAfterHostVerification() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));

            executor(() -> stub.client, publicTargets(publicAddress())).execute("conn", passwordConfig(), command());

            var order = inOrder(stub.client, stub.connectFuture, stub.session, stub.authFuture);
            order.verify(stub.client).connect(anyString(), any(SocketAddress.class), isNull(), isNull());
            order.verify(stub.connectFuture).verify(Duration.ofSeconds(10));
            order.verify(stub.connectFuture).getSession();
            order.verify(stub.session).addPasswordIdentity("s3cret");
            order.verify(stub.session).auth();
            order.verify(stub.authFuture).verify(Duration.ofSeconds(10));
        }

        @Test
        @DisplayName("ключевая аутентификация добавляет ровно одну пару ключей")
        void keyCredentialsAreAddedAsPublicKeyIdentity() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));

            executor(() -> stub.client, publicTargets(publicAddress())).execute("conn", keyConfig(), command());

            verify(stub.session, times(1)).addPublicKeyIdentity(any(KeyPair.class));
            verify(stub.session, never()).addPasswordIdentity(anyString());
        }

        @Test
        @DisplayName("ошибка аутентификации не повторяется и безопасна")
        void authFailureDoesNotFallBack() throws Exception {
            PublicTargets targets = publicTargets(publicAddress(), otherPublicAddress());
            ClientStub stub = new ClientStub();
            stub.failAuthentication(new IOException("auth boom"));
            AtomicInteger clientsCreated = new AtomicInteger();

            ConnectorException failure = assertThrows(ConnectorException.class,
                    () -> executor(() -> {
                        clientsCreated.incrementAndGet();
                        return stub.client;
                    }, targets).execute("conn", passwordConfig(), command()));

            assertEquals(1, clientsCreated.get());
            assertTrue(failure.getMessage().contains("authentication"), failure.getMessage());
            assertFalse(failure.getMessage().contains("boom"), failure.getMessage());
            verify(stub.session).addPasswordIdentity("s3cret");
            verify(stub.session, never()).createExecChannel(anyString());
            verify(stub.session).close();
            verify(stub.client).stop();
        }

        @Test
        @DisplayName("проверка заканчивается аутентификацией и не создаёт exec-канал")
        void validationAuthenticatesWithoutCommand() throws Exception {
            ClientStub stub = new ClientStub();

            executor(() -> stub.client, publicTargets(publicAddress())).validate(passwordConfig());

            verify(stub.client).start();
            verify(stub.session).addPasswordIdentity("s3cret");
            verify(stub.session).auth();
            verify(stub.session, never()).createExecChannel(anyString());
            verify(stub.session).close();
            verify(stub.client).stop();
        }

        @Test
        @DisplayName("клиент игнорирует локальные ~/.ssh, agent и keyboard-interactive")
        void clientIgnoresLocalSshConfiguration() throws Exception {
            ClientStub stub = new ClientStub();

            executor(() -> stub.client, publicTargets(publicAddress())).validate(passwordConfig());

            verify(stub.client).setHostConfigEntryResolver(HostConfigEntryResolver.EMPTY);
            verify(stub.client).setKeyIdentityProvider(KeyIdentityProvider.EMPTY_KEYS_PROVIDER);
            verify(stub.client).setUserInteraction(UserInteraction.NONE);
            verify(stub.client).setAgentFactory(isNull());
            @SuppressWarnings("unchecked")
            var factories = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(stub.client).setUserAuthFactories(factories.capture());
            List<?> configured = factories.getValue();
            assertEquals(1, configured.size());
            assertInstanceOf(UserAuthPasswordFactory.class, configured.getFirst());
        }

        @Test
        @DisplayName("ключевой конфиг ставит ровно одну public-key фабрику")
        void keyConfigUsesExactlyOnePublicKeyFactory() throws Exception {
            ClientStub stub = new ClientStub();

            executor(() -> stub.client, publicTargets(publicAddress())).validate(keyConfig());

            @SuppressWarnings("unchecked")
            var factories = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(stub.client).setUserAuthFactories(factories.capture());
            List<?> configured = factories.getValue();
            assertEquals(1, configured.size());
            assertInstanceOf(UserAuthPublicKeyFactory.class, configured.getFirst());
        }

        @Test
        @DisplayName("SHA-1 подписи и key exchange не включаются")
        void legacySha1IsNotEnabled() throws Exception {
            ClientStub stub = new ClientStub();

            executor(() -> stub.client, publicTargets(publicAddress())).validate(passwordConfig());

            @SuppressWarnings("unchecked")
            var signatures = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(stub.client).setSignatureFactories(signatures.capture());
            List<String> signatureNames = ((List<NamedFactory<Signature>>) signatures.getValue()).stream()
                    .map(NamedFactory::getName)
                    .toList();
            assertFalse(signatureNames.stream().anyMatch(name -> name.startsWith("ssh-rsa")
                    || name.startsWith("ssh-dss")), signatureNames.toString());
            assertTrue(signatureNames.contains("rsa-sha2-256"), signatureNames.toString());
            assertTrue(signatureNames.contains("ssh-ed25519"), signatureNames.toString());

            @SuppressWarnings("unchecked")
            var exchanges = org.mockito.ArgumentCaptor.forClass(List.class);
            verify(stub.client).setKeyExchangeFactories(exchanges.capture());
            List<String> exchangeNames = ((List<KeyExchangeFactory>) exchanges.getValue()).stream()
                    .map(KeyExchangeFactory::getName)
                    .toList();
            assertFalse(exchangeNames.contains("diffie-hellman-group1-sha1"), exchangeNames.toString());
            assertFalse(exchangeNames.contains("diffie-hellman-group14-sha1"), exchangeNames.toString());
            assertFalse(exchangeNames.contains("diffie-hellman-group-exchange-sha1"), exchangeNames.toString());
            assertTrue(exchangeNames.contains("curve25519-sha256"), exchangeNames.toString());
        }
    }

    // ---- command execution ------------------------------------------------

    @Nested
    @DisplayName("выполнение команды")
    class Execution {

        @Test
        @DisplayName("ненулевой exit code — обычный результат")
        void nonZeroExitIsNormalResult() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(7, EnumSet.of(ClientChannelEvent.CLOSED));
            streamDuringWait(stub.channel, "out".getBytes(StandardCharsets.UTF_8),
                    "err".getBytes(StandardCharsets.UTF_8), EnumSet.of(ClientChannelEvent.CLOSED));

            SshCommandResult result = executor(() -> stub.client, publicTargets(publicAddress()))
                    .execute("conn", passwordConfig(), command());

            assertEquals("out", result.stdout());
            assertEquals("err", result.stderr());
            assertEquals(7, result.exitCode());
            assertFalse(result.timedOut());
            assertFalse(result.truncated());
            verify(stub.channel).close();
            verify(stub.session).close();
            verify(stub.client).stop();
        }

        @Test
        @DisplayName("оба потока подключены до open()")
        void bothStreamsAreInstalledBeforeOpen() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));

            executor(() -> stub.client, publicTargets(publicAddress())).execute("conn", passwordConfig(), command());

            var order = inOrder(stub.channel);
            order.verify(stub.channel).setOut(any(OutputStream.class));
            order.verify(stub.channel).setErr(any(OutputStream.class));
            order.verify(stub.channel).open();
            var stdout = org.mockito.ArgumentCaptor.forClass(OutputStream.class);
            var stderr = org.mockito.ArgumentCaptor.forClass(OutputStream.class);
            verify(stub.channel).setOut(stdout.capture());
            verify(stub.channel).setErr(stderr.capture());
            assertInstanceOf(SshOutputCollector.class, stdout.getValue());
            assertInstanceOf(SshOutputCollector.class, stderr.getValue());
            assertNotEquals(stdout.getValue(), stderr.getValue());
        }

        @Test
        @DisplayName("таймаут команды возвращает частичный вывод и закрывает канал")
        void commandTimeoutReturnsPartialOutputAndCloses() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(null, EnumSet.of(ClientChannelEvent.TIMEOUT));
            streamDuringWait(stub.channel, "partial".getBytes(StandardCharsets.UTF_8),
                    "problem".getBytes(StandardCharsets.UTF_8), EnumSet.of(ClientChannelEvent.TIMEOUT));

            SshCommandResult result = executor(() -> stub.client, publicTargets(publicAddress()))
                    .execute("conn", passwordConfig(), command());

            assertEquals("partial", result.stdout());
            assertEquals("problem", result.stderr());
            assertTrue(result.timedOut());
            assertNull(result.exitCode());
            verify(stub.channel).close();
            verify(stub.session).close();
            verify(stub.client).stop();
        }

        @Test
        @DisplayName("сбой создания канала не повторяется и не раскрывает текст библиотеки")
        void execFailureDoesNotRetry() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));
            when(stub.session.createExecChannel(COMMAND)).thenThrow(new IOException("channel boom"));

            ConnectorException failure = assertThrows(ConnectorException.class,
                    () -> executor(() -> stub.client, publicTargets(publicAddress()))
                            .execute("conn", passwordConfig(), command()));

            assertTrue(failure.getMessage().contains("Unable to start the SSH command"), failure.getMessage());
            assertFalse(failure.getMessage().contains("boom"), failure.getMessage());
            verify(stub.client, times(1)).connect(anyString(), any(SocketAddress.class), any(), any());
            verify(stub.session, times(1)).createExecChannel(COMMAND);
            verify(stub.session).close();
            verify(stub.client).stop();
        }

        @Test
        @DisplayName("обрыв после отправки exec даёт неизвестный исход без повтора")
        void disconnectAfterExecHasUnknownOutcome() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));
            stub.failOpen(new IOException("disconnect"));

            ConnectorException failure = assertThrows(ConnectorException.class,
                    () -> executor(() -> stub.client, publicTargets(publicAddress()))
                            .execute("conn", passwordConfig(), command()));

            assertTrue(failure.getMessage().contains("unknown"), failure.getMessage());
            assertFalse(failure.getMessage().contains("disconnect"), failure.getMessage());
            verify(stub.client, times(1)).connect(anyString(), any(SocketAddress.class), any(), any());
            verify(stub.session, times(1)).createExecChannel(COMMAND);
            verify(stub.channel).close();
            verify(stub.session).close();
            verify(stub.client).stop();
        }
    }

    // ---- limits -----------------------------------------------------------

    @Nested
    @DisplayName("лимиты одновременных вызовов")
    class Limits {

        @Test
        @DisplayName("не более 16 одновременных вызовов узла")
        void nodeLimitIsSixteen() throws Exception {
            CountDownLatch entered = new CountDownLatch(16);
            CountDownLatch release = new CountDownLatch(1);
            List<ClientStub> stubs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                stubs.add(blockingClient(entered, release, 0));
            }
            AtomicInteger next = new AtomicInteger();
            SshExecutor executor = executor(() -> stubs.get(next.getAndIncrement()).client,
                    publicTargets(publicAddress()));
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                List<Future<SshCommandResult>> running = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    String connectionId = "connection-" + i;
                    running.add(pool.submit(() -> executor.execute(connectionId, passwordConfig(), command())));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "16 calls must reach the connect stage");

                ConnectorException busy = assertThrows(ConnectorException.class,
                        () -> executor.execute("connection-16", passwordConfig(), command()));
                assertTrue(busy.getMessage().toLowerCase().contains("busy"), busy.getMessage());
                assertEquals(16, next.get());

                release.countDown();
                for (Future<SshCommandResult> future : running) {
                    assertEquals(0, future.get(10, TimeUnit.SECONDS).exitCode());
                }
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("не более 2 одновременных команд на одно подключение")
        void connectionLimitIsTwo() throws Exception {
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            List<ClientStub> stubs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                stubs.add(blockingClient(entered, release, 0));
            }
            stubs.add(new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED)));
            AtomicInteger next = new AtomicInteger();
            SshExecutor executor = executor(() -> stubs.get(next.getAndIncrement()).client,
                    publicTargets(publicAddress()));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<SshCommandResult>> running = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    running.add(pool.submit(() -> executor.execute("same-connection", passwordConfig(), command())));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "both calls must reach the connect stage");

                ConnectorException busy = assertThrows(ConnectorException.class,
                        () -> executor.execute("same-connection", passwordConfig(), command()));
                assertTrue(busy.getMessage().toLowerCase().contains("concurrent"), busy.getMessage());
                assertEquals(2, next.get());

                release.countDown();
                for (Future<SshCommandResult> future : running) {
                    assertEquals(0, future.get(10, TimeUnit.SECONDS).exitCode());
                }
                assertEquals(0, executor.execute("same-connection", passwordConfig(), command()).exitCode());
                assertEquals(3, next.get());
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("validate занимает node permit и не выполняет команду")
        void validationUsesNodePermitWithoutCommand() throws Exception {
            CountDownLatch entered = new CountDownLatch(16);
            CountDownLatch release = new CountDownLatch(1);
            List<ClientStub> stubs = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                stubs.add(blockingClient(entered, release, 0));
            }
            AtomicInteger next = new AtomicInteger();
            SshExecutor executor = executor(() -> stubs.get(next.getAndIncrement()).client,
                    publicTargets(publicAddress()));
            ExecutorService pool = Executors.newFixedThreadPool(16);
            try {
                List<Future<?>> running = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    running.add(pool.submit(() -> executor.validate(passwordConfig())));
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "16 validations must reach the connect stage");

                ConnectorException busy = assertThrows(ConnectorException.class,
                        () -> executor.validate(passwordConfig()));
                assertTrue(busy.getMessage().toLowerCase().contains("busy"), busy.getMessage());
                assertEquals(16, next.get());

                release.countDown();
                for (Future<?> future : running) {
                    future.get(10, TimeUnit.SECONDS);
                }
                for (ClientStub stub : stubs) {
                    verify(stub.session, never()).createExecChannel(anyString());
                }
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("permit освобождается после любого отказа")
        void permitsReleaseAfterEveryFailure() throws Exception {
            repeatFailures(executorWithFailingResolve(), "Unable to resolve");
            repeatFailures(executorWithRefusedTarget(), "not allowed");
            repeatFailures(executorWithTcpFailure(), "Unable to establish");
            repeatFailures(executorWithAuthFailure(), "authentication");
            repeatFailures(executorWithChannelFailure(), "Unable to start the SSH command");
            repeatFailures(executorWithUnknownOutcome(), "unknown");
            repeatValidationFailures(executorWithFailingResolve(), "Unable to resolve");
        }

        private void repeatFailures(SshExecutor executor, String expected) {
            for (int i = 0; i < 17; i++) {
                ConnectorException failure = assertThrows(ConnectorException.class,
                        () -> executor.execute("conn", passwordConfig(), command()));
                assertTrue(failure.getMessage().contains(expected), failure.getMessage());
                assertNoPermitLeak(failure.getMessage());
            }
        }

        private void repeatValidationFailures(SshExecutor executor, String expected) {
            for (int i = 0; i < 17; i++) {
                ConnectorException failure = assertThrows(ConnectorException.class,
                        () -> executor.validate(passwordConfig()));
                assertTrue(failure.getMessage().contains(expected), failure.getMessage());
                assertNoPermitLeak(failure.getMessage());
            }
        }

        private SshExecutor executorWithFailingResolve() throws Exception {
            PublicTargets targets = mock(PublicTargets.class);
            when(targets.resolve(anyString())).thenThrow(new UnknownHostException("missing"));
            return executor(() -> {
                throw new AssertionError("no client for an unresolvable host");
            }, targets);
        }

        private SshExecutor executorWithRefusedTarget() throws Exception {
            InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
            PublicTargets targets = mock(PublicTargets.class);
            when(targets.resolve(anyString())).thenReturn(new InetAddress[]{loopback});
            doThrow(new TargetNotAllowedException("not allowed")).when(targets).requirePublic(loopback);
            return executor(() -> {
                throw new AssertionError("no client for a refused target");
            }, targets);
        }

        private SshExecutor executorWithTcpFailure() throws Exception {
            ClientStub stub = new ClientStub();
            when(stub.client.connect(anyString(), any(SocketAddress.class), any(), any()))
                    .thenThrow(new ConnectException("refused"));
            return executor(() -> stub.client, publicTargets(publicAddress()));
        }

        private SshExecutor executorWithAuthFailure() throws Exception {
            ClientStub stub = new ClientStub();
            stub.failAuthentication(new IOException("auth boom"));
            return executor(() -> stub.client, publicTargets(publicAddress()));
        }

        private SshExecutor executorWithChannelFailure() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));
            when(stub.session.createExecChannel(COMMAND)).thenThrow(new IOException("channel boom"));
            return executor(() -> stub.client, publicTargets(publicAddress()));
        }

        private SshExecutor executorWithUnknownOutcome() throws Exception {
            ClientStub stub = new ClientStub().servingCommand(0, EnumSet.of(ClientChannelEvent.CLOSED));
            stub.failOpen(new IOException("disconnect"));
            return executor(() -> stub.client, publicTargets(publicAddress()));
        }
    }

    // ---- production wiring ------------------------------------------------

    @Test
    @DisplayName("production-конструктор создаёт PublicTargets без HTTP-прокси")
    void productionConstructorHasNoEgressProxy() throws Exception {
        SshExecutor guarded = new SshExecutor(false);
        Field targetsField = SshExecutor.class.getDeclaredField("targets");
        targetsField.setAccessible(true);
        PublicTargets targets = (PublicTargets) targetsField.get(guarded);
        assertFalse(targets.allowsPrivate());
        Field proxyField = PublicTargets.class.getDeclaredField("proxy");
        proxyField.setAccessible(true);
        assertSame(EgressProxy.NONE, proxyField.get(targets));

        SshExecutor permissive = new SshExecutor(true);
        PublicTargets permissiveTargets = (PublicTargets) targetsField.get(permissive);
        assertTrue(permissiveTargets.allowsPrivate());
    }
}
