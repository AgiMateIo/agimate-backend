package ru.agimate.controlapi.connectors.integrations.ssh;

import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.net.SocketAddress;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Accepts exactly the host key whose OpenSSH {@code SHA256:} fingerprint was pinned when the
 * connection was created. The fingerprint is validated in the constructor, so a malformed value
 * fails before any network call; {@link #lastMismatch()} then carries a safe expected/actual pair
 * for the owner, never key material.
 */
public class SshHostKeyVerifier implements ServerKeyVerifier {

    private static final String PREFIX = "SHA256:";
    private static final Pattern OPENSSH_SHA256 = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}");

    private final String expectedFingerprint;
    private volatile String lastMismatch;

    public SshHostKeyVerifier(String expectedFingerprint) {
        String normalized = normalizeFingerprint(expectedFingerprint);
        if (normalized == null) {
            throw new ConnectorException("Invalid SSH host key fingerprint");
        }
        this.expectedFingerprint = normalized;
    }

    @Override
    public boolean verifyServerKey(ClientSession session, SocketAddress remoteAddress, PublicKey serverKey) {
        // The explicit digest keeps this independent of MINA's mutable process-wide default.
        Map.Entry<Boolean, String> check =
                KeyUtils.checkFingerPrint(expectedFingerprint, BuiltinDigests.sha256, serverKey);
        if (Boolean.TRUE.equals(check.getKey())) {
            lastMismatch = null;
            return true;
        }
        lastMismatch = "expected " + expectedFingerprint + " / actual " + check.getValue();
        return false;
    }

    public String fingerprint(PublicKey key) {
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
    }

    /** The last mismatch as {@code expected … / actual …}, or {@code null} before one was seen. */
    public String lastMismatch() {
        return lastMismatch;
    }

    /**
     * Returns the trimmed fingerprint when it is an unpadded OpenSSH {@code SHA256:} value, otherwise
     * {@code null}. Shared with {@link SshConnectionConfig} so both reject the same shapes.
     */
    static String normalizeFingerprint(String fingerprint) {
        if (fingerprint == null) {
            return null;
        }
        String trimmed = fingerprint.trim();
        if (!OPENSSH_SHA256.matcher(trimmed).matches()) {
            return null;
        }
        try {
            byte[] digest = Base64.getDecoder().decode(trimmed.substring(PREFIX.length()));
            return digest.length == 32 ? trimmed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
