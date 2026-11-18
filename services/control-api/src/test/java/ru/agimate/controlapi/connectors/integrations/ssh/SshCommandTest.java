package ru.agimate.controlapi.connectors.integrations.ssh;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SshCommand")
class SshCommandTest {

    @Nested
    @DisplayName("команда")
    class Command {

        @Test
        @DisplayName("по умолчанию таймаут 60 секунд")
        void defaultTimeout() {
            assertEquals(Duration.ofSeconds(60), SshCommand.of("uptime", null).timeout());
        }

        @Test
        @DisplayName("сохраняет команду дословно, без обрезки пробелов")
        void verbatim() {
            assertEquals("  uptime -p  ", SshCommand.of("  uptime -p  ", 10).command());
        }

        @Test
        @DisplayName("отклоняет пустую команду")
        void blankCommand() {
            assertThrows(ConnectorException.class, () -> SshCommand.of("   ", 10));
            assertThrows(ConnectorException.class, () -> SshCommand.of(null, 10));
        }

        @Test
        @DisplayName("принимает команду ровно в 65536 байт UTF-8")
        void maxBytesAccepted() {
            String command = "a".repeat(65_536);

            assertEquals(command, SshCommand.of(command, 10).command());
        }

        @Test
        @DisplayName("отклоняет команду больше 65536 байт UTF-8")
        void overMaxBytesRejected() {
            assertThrows(ConnectorException.class,
                    () -> SshCommand.of("a".repeat(65_537), 10));
        }

        @Test
        @DisplayName("считает байты UTF-8, а не символы")
        void countsUtf8Bytes() {
            String twoByteChars = "я".repeat(40_000); // 80 000 UTF-8 bytes, 40 000 chars
            String exactlyAtLimit = "я".repeat(32_768); // 65 536 UTF-8 bytes

            assertThrows(ConnectorException.class, () -> SshCommand.of(twoByteChars, 10));
            assertEquals(exactlyAtLimit, SshCommand.of(exactlyAtLimit, 10).command());
        }

        @Test
        @DisplayName("не повторяет команду в сообщении об ошибке")
        void rejectionDoesNotEchoCommand() {
            String command = "x".repeat(65_537);

            ConnectorException exception = assertThrows(ConnectorException.class,
                    () -> SshCommand.of(command, 10));

            assertFalse(exception.getMessage().contains("xxxx"));
        }

        @Test
        @DisplayName("toString не содержит текст команды")
        void toStringOmitsCommand() {
            SshCommand command = SshCommand.of("UNIQUE-COMMAND-MARKER --flag", 10);

            String rendered = command.toString();

            assertFalse(rendered.contains("UNIQUE-COMMAND-MARKER"));
            assertTrue(rendered.contains("commandChars=28"));
            assertTrue(rendered.contains("timeout=PT10S"));
        }
    }

    @Nested
    @DisplayName("диапазон таймаута")
    class TimeoutRange {

        @Test
        @DisplayName("принимает границы 1 и 1800 секунд")
        void bounds() {
            assertEquals(Duration.ofSeconds(1), SshCommand.of("true", 1).timeout());
            assertEquals(Duration.ofSeconds(1800), SshCommand.of("true", 1800).timeout());
        }

        @Test
        @DisplayName("отклоняет 0, 1801 и отрицательные значения")
        void outsideBounds() {
            assertThrows(ConnectorException.class, () -> SshCommand.of("true", 0));
            assertThrows(ConnectorException.class, () -> SshCommand.of("true", 1801));
            assertThrows(ConnectorException.class, () -> SshCommand.of("true", -5));
        }
    }

    @Nested
    @DisplayName("приведение timeoutSeconds(Object)")
    class TimeoutSeconds {

        @Test
        @DisplayName("null даёт таймаут по умолчанию 60 секунд")
        void nullDefault() {
            assertEquals(60, SshCommand.timeoutSeconds(null));
        }

        @Test
        @DisplayName("приводит целые числа и числовые строки")
        void integralValues() {
            assertEquals(30, SshCommand.timeoutSeconds(30));
            assertEquals(30, SshCommand.timeoutSeconds(30L));
            assertEquals(30, SshCommand.timeoutSeconds((short) 30));
            assertEquals(30, SshCommand.timeoutSeconds((byte) 30));
            assertEquals(30, SshCommand.timeoutSeconds(BigInteger.valueOf(30)));
            assertEquals(30, SshCommand.timeoutSeconds("30"));
            assertEquals(30, SshCommand.timeoutSeconds(" 30 "));
            assertEquals(1, SshCommand.timeoutSeconds("1"));
            assertEquals(1800, SshCommand.timeoutSeconds("1800"));
        }

        @Test
        @DisplayName("отклоняет дробные числа")
        void fractionalNumbers() {
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(30.0));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(1.5f));
        }

        @Test
        @DisplayName("отклоняет нечисловые значения")
        void nonNumericValues() {
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(true));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(Map.of("seconds", 30)));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(""));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds("   "));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds("abc"));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds("30s"));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds("30.0"));
        }

        @Test
        @DisplayName("отклоняет числа вне диапазона")
        void outOfRange() {
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(0));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds(1801));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds("-1"));
            assertThrows(ConnectorException.class, () -> SshCommand.timeoutSeconds("99999999999999999999"));
        }
    }

    @Nested
    @DisplayName("результат")
    class Result {

        @Test
        @DisplayName("возвращает каждое поле как есть")
        void plainRecord() {
            SshCommandResult result = new SshCommandResult("out", "err", 7, true, false, 1234L);

            assertEquals("out", result.stdout());
            assertEquals("err", result.stderr());
            assertEquals(7, result.exitCode());
            assertTrue(result.timedOut());
            assertFalse(result.truncated());
            assertEquals(1234L, result.durationMs());
        }

        @Test
        @DisplayName("неизвестный код возврата остаётся null")
        void unknownExitCode() {
            assertNull(new SshCommandResult("", "", null, true, false, 1L).exitCode());
        }

        @Test
        @DisplayName("toString не содержит stdout и stderr")
        void toStringOmitsOutput() {
            SshCommandResult result = new SshCommandResult(
                    "UNIQUE-STDOUT-MARKER", "UNIQUE-STDERR-MARKER", 1, false, true, 5L);

            String rendered = result.toString();

            assertFalse(rendered.contains("UNIQUE-STDOUT-MARKER"));
            assertFalse(rendered.contains("UNIQUE-STDERR-MARKER"));
            assertTrue(rendered.contains("exitCode=1"));
            assertTrue(rendered.contains("timedOut=false"));
            assertTrue(rendered.contains("truncated=true"));
            assertTrue(rendered.contains("durationMs=5"));
        }
    }
}
