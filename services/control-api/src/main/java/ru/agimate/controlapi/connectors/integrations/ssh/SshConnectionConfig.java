package ru.agimate.controlapi.connectors.integrations.ssh;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.Locale;
import java.util.Map;

/**
 * One SSH account on one server, built from the connector's credential form. The host, port,
 * username and fingerprint are open values; the private key, its passphrase and the password are
 * authentication secrets. Exactly one of the key and the password is present. The record's
 * generated {@code toString()} is overridden because it would otherwise spell every secret out.
 */
public record SshConnectionConfig(
        String host,
        int port,
        String username,
        String hostKeyFingerprint,
        String privateKey,
        String privateKeyPassphrase,
        String password) {

    public static final int DEFAULT_PORT = 22;

    public SshConnectionConfig {
        host = normalizeHost(host);
        port = requirePort(port);
        username = normalizeUsername(username);
        hostKeyFingerprint = requireFingerprint(hostKeyFingerprint);
        privateKey = presentOrNull(privateKey);
        privateKeyPassphrase = presentOrNull(privateKeyPassphrase);
        password = presentOrNull(password);
        if (privateKey != null && password != null) {
            throw new ValidationException("password", "Configure either an SSH private key or a password, not both");
        }
        if (privateKey == null && password == null) {
            throw new ValidationException("privateKey", "Configure an SSH private key or a password");
        }
        if (privateKeyPassphrase != null && privateKey == null) {
            throw new ValidationException("privateKeyPassphrase", "A private key passphrase requires a private key");
        }
    }

    public static SshConnectionConfig fromCredentials(Map<String, String> credentials) {
        Map<String, String> values = credentials == null ? Map.of() : credentials;
        return new SshConnectionConfig(
                values.get("host"),
                parsePort(values.get("port")),
                values.get("username"),
                values.get("hostKeyFingerprint"),
                values.get("privateKey"),
                values.get("privateKeyPassphrase"),
                values.get("password"));
    }

    /** {@code username@host:port}; the stable part of the connection's identity. */
    public String identifier() {
        return username + "@" + host + ":" + port;
    }

    public String displayName() {
        return "SSH: " + identifier();
    }

    /** The canonical open values written back to {@code connections.params}. */
    public Map<String, String> derivedCredentials() {
        return Map.of("host", host, "port", Integer.toString(port));
    }

    @Override
    public String toString() {
        return "SshConnectionConfig[host=" + host + ", port=" + port + ", username=" + username
                + ", hostKeyFingerprint=" + hostKeyFingerprint
                + ", privateKey=" + redacted(privateKey)
                + ", privateKeyPassphrase=" + redacted(privateKeyPassphrase)
                + ", password=" + redacted(password) + "]";
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new ValidationException("host", "SSH host is required");
        }
        return value;
    }

    private static String normalizeUsername(String username) {
        String value = username == null ? "" : username.trim();
        if (value.isEmpty()) {
            throw new ValidationException("username", "SSH username is required");
        }
        return value;
    }

    private static String requireFingerprint(String fingerprint) {
        String normalized = SshHostKeyVerifier.normalizeFingerprint(fingerprint);
        if (normalized == null) {
            throw new ValidationException("hostKeyFingerprint", "SSH host key fingerprint must be an OpenSSH SHA256: value");
        }
        return normalized;
    }

    private static int parsePort(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PORT;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ValidationException("port", "SSH port must be a whole number between 1 and 65535");
        }
    }

    private static int requirePort(int port) {
        if (port < 1 || port > 65535) {
            throw new ValidationException("port", "SSH port must be between 1 and 65535");
        }
        return port;
    }

    private static String presentOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String redacted(String value) {
        return value == null ? "null" : "<redacted>";
    }

    /** A rejected form field, tagged so the connector facade can report the exact input. */
    public static class ValidationException extends ConnectorException {

        private final String field;

        public ValidationException(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
