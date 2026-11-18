package ru.agimate.controlapi.connectors.integrations.ssh;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A command validated at the tool boundary. The command text is kept verbatim: trimming it would
 * change what the remote shell receives. The timeout is the remote command's own budget, not the
 * platform's wait for the tool result.
 */
public record SshCommand(String command, Duration timeout) {

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final int MAX_TIMEOUT_SECONDS = 1800;
    private static final int MAX_COMMAND_BYTES = 65_536;

    public SshCommand {
        if (command == null || command.isBlank()) {
            throw new ConnectorException("SSH command must not be blank");
        }
        if (command.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_BYTES) {
            throw new ConnectorException("SSH command must be at most 65536 UTF-8 bytes");
        }
        if (timeout == null
                || timeout.compareTo(Duration.ofSeconds(MIN_TIMEOUT_SECONDS)) < 0
                || timeout.compareTo(Duration.ofSeconds(MAX_TIMEOUT_SECONDS)) > 0) {
            throw new ConnectorException("SSH command timeout must be between 1 and 1800 seconds");
        }
    }

    public static SshCommand of(String command, Integer timeoutSeconds) {
        return new SshCommand(command, Duration.ofSeconds(timeoutSeconds(timeoutSeconds)));
    }

    /**
     * Coerces a tool argument the way Jackson would for an integer field: absent means the default,
     * an integral number or numeric string is accepted, and anything else is rejected rather than
     * silently truncated.
     */
    static int timeoutSeconds(Object raw) {
        if (raw == null) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        Long seconds = null;
        if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long) {
            seconds = ((Number) raw).longValue();
        } else if (raw instanceof BigInteger value) {
            seconds = value.bitLength() < Long.SIZE ? value.longValue() : null;
        } else if (raw instanceof String value) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                try {
                    seconds = Long.parseLong(trimmed);
                } catch (NumberFormatException e) {
                    throw invalidTimeout();
                }
            }
        }
        if (seconds == null || seconds < MIN_TIMEOUT_SECONDS || seconds > MAX_TIMEOUT_SECONDS) {
            throw invalidTimeout();
        }
        return (int) (long) seconds;
    }

    private static ConnectorException invalidTimeout() {
        return new ConnectorException("SSH command timeout must be a whole number between 1 and 1800 seconds");
    }

    /** Lengths and timeout only: a logged record must not repeat the command text. */
    @Override
    public String toString() {
        return "SshCommand[commandChars=" + command.length()
                + ", commandBytes=" + command.getBytes(StandardCharsets.UTF_8).length
                + ", timeout=" + timeout + "]";
    }
}
