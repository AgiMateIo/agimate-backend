package ru.agimate.controlapi.connectors.integrations.ssh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Manual check against a real sshd, enabled with {@code -Dagimate.ssh.live=true}. Parameters come
 * from {@code AGIMATE_SSH_TEST_*} environment variables and are never printed or committed. The test
 * never runs in the normal suite; without the flag the whole class is skipped.
 */
@EnabledIfSystemProperty(named = "agimate.ssh.live", matches = "true")
@DisplayName("SshExecutor (live sshd)")
class SshExecutorLiveTest {

    private static final String HOST = System.getenv("AGIMATE_SSH_TEST_HOST");
    private static final String PORT = System.getenv("AGIMATE_SSH_TEST_PORT");
    private static final String USERNAME = System.getenv("AGIMATE_SSH_TEST_USERNAME");
    private static final String FINGERPRINT = System.getenv("AGIMATE_SSH_TEST_FINGERPRINT");
    private static final String PRIVATE_KEY = System.getenv("AGIMATE_SSH_TEST_PRIVATE_KEY");
    private static final String PRIVATE_KEY_PASSPHRASE =
            System.getenv("AGIMATE_SSH_TEST_PRIVATE_KEY_PASSPHRASE");
    private static final String PASSWORD = System.getenv("AGIMATE_SSH_TEST_PASSWORD");
    private static final boolean ALLOW_PRIVATE =
            "true".equalsIgnoreCase(System.getenv("AGIMATE_SSH_TEST_ALLOW_PRIVATE"));

    private SshExecutor executor;

    @BeforeEach
    void requireStand() {
        assumeTrue(present(HOST), "AGIMATE_SSH_TEST_HOST is required");
        assumeTrue(present(USERNAME), "AGIMATE_SSH_TEST_USERNAME is required");
        assumeTrue(present(FINGERPRINT), "AGIMATE_SSH_TEST_FINGERPRINT is required");
        assumeTrue(present(PRIVATE_KEY) || present(PASSWORD),
                "either AGIMATE_SSH_TEST_PRIVATE_KEY or AGIMATE_SSH_TEST_PASSWORD is required");
        executor = new SshExecutor(ALLOW_PRIVATE);
    }

    @Test
    @DisplayName("рукопожатие и аутентификация без команды")
    void validateSucceeds() {
        executor.validate(config(FINGERPRINT));
    }

    @Test
    @DisplayName("stdout и stderr разделены, ненулевой exit code сохранён")
    void commandSeparatesStreamsAndKeepsExitCode() {
        SshCommandResult result = executor.execute("live", config(FINGERPRINT),
                new SshCommand("printf out; printf err >&2; exit 7", Duration.ofSeconds(15)));

        assertEquals("out", result.stdout());
        assertEquals("err", result.stderr());
        assertEquals(7, result.exitCode());
        assertFalse(result.timedOut());
        assertFalse(result.truncated());
    }

    @Test
    @DisplayName("неверный fingerprint отклоняется")
    void wrongFingerprintIsRejected() {
        ConnectorException failure = assertThrows(ConnectorException.class,
                () -> executor.validate(config(differentFingerprint(FINGERPRINT))));

        assertTrue(failure.getMessage().contains("does not match")
                || failure.getMessage().contains("Unable to establish"), failure.getMessage());
    }

    @Test
    @DisplayName("таймаут команды оставляет частичный вывод")
    void commandTimeoutKeepsPartialOutput() {
        SshCommandResult result = executor.execute("live", config(FINGERPRINT),
                new SshCommand("printf before; sleep 10; printf after", Duration.ofSeconds(1)));

        assertTrue(result.timedOut());
        assertNull(result.exitCode());
        assertEquals("before", result.stdout());
    }

    @Test
    @DisplayName("каждый поток независимо ограничен 32 КиБ")
    void bothStreamsAreCappedIndependently() {
        SshCommandResult result = executor.execute("live", config(FINGERPRINT),
                new SshCommand("yes a | head -c 40000; yes b | head -c 40000 >&2", Duration.ofSeconds(30)));

        assertTrue(result.truncated());
        assertEquals(SshOutputCollector.BYTE_LIMIT, result.stdout().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(SshOutputCollector.BYTE_LIMIT, result.stderr().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertEquals(0, result.exitCode());
    }

    private static SshConnectionConfig config(String fingerprint) {
        if (present(PRIVATE_KEY)) {
            return new SshConnectionConfig(HOST, port(), USERNAME, fingerprint,
                    PRIVATE_KEY, PRIVATE_KEY_PASSPHRASE, null);
        }
        return new SshConnectionConfig(HOST, port(), USERNAME, fingerprint, null, null, PASSWORD);
    }

    private static int port() {
        return present(PORT) ? Integer.parseInt(PORT.trim()) : SshConnectionConfig.DEFAULT_PORT;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String differentFingerprint(String fingerprint) {
        char last = fingerprint.charAt(fingerprint.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return fingerprint.substring(0, fingerprint.length() - 1) + replacement;
    }
}
