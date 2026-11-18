package ru.agimate.controlapi.connectors.integrations.ssh;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SshToolService")
class SshToolServiceTest {

    private static final String CONNECTION_ID = UUID.randomUUID().toString();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String FINGERPRINT = "SHA256:XPK5yL0IgFh+Si4dkHZDeL7QuwuHYFRqH/+9dPXcS0M";
    private static final String PRIVATE_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nS3CRET-KEY-BODY\n-----END OPENSSH PRIVATE KEY-----\n";
    private static final String TOOL = "run_command";

    @Mock
    private SshExecutor executor;

    private SshConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = new SshConnectorService(new SshToolService(executor), executor);
    }

    private static Map<String, String> credentials() {
        Map<String, String> values = new HashMap<>();
        values.put("host", "example.com");
        values.put("username", "root");
        values.put("hostKeyFingerprint", FINGERPRINT);
        values.put("privateKey", PRIVATE_KEY);
        return values;
    }

    private static ConnectorEnv env() {
        return new ConnectorEnv(CONNECTION_ID, USER_ID, null, null, null, null, credentials(), null);
    }

    private static Map<String, Object> args(Object command, Object timeoutSeconds) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("command", command);
        if (timeoutSeconds != null) {
            values.put("timeoutSeconds", timeoutSeconds);
        }
        return values;
    }

    @Nested
    @DisplayName("схема тула")
    class Schema {

        @Test
        @DisplayName("только command (обязательный) и timeoutSeconds (необязательный)")
        void inputSchema() {
            ConnectorToolSpec spec = handler.getTools().get(TOOL);

            assertNotNull(spec);
            JsonSchema schema = spec.inputSchema();
            assertEquals("object", schema.type());
            assertEquals(Set.of("command", "timeoutSeconds"), schema.properties().keySet());
            assertEquals(List.of("command"), schema.required());
            assertEquals("string", schema.properties().get("command").type());
            assertEquals("integer", schema.properties().get("timeoutSeconds").type());
        }

        @Test
        @DisplayName("описание таймаута называет диапазон и значение по умолчанию")
        void timeoutDescription() {
            JsonSchema timeout = handler.getTools().get(TOOL).inputSchema().properties().get("timeoutSeconds");

            assertTrue(timeout.description().contains("1-1800"));
            assertTrue(timeout.description().contains("60"));
        }

        @Test
        @DisplayName("результат описан как объект с stdout/stderr/exitCode")
        void outputSchema() {
            JsonSchema output = handler.getTools().get(TOOL).outputSchema();

            assertNotNull(output);
            assertEquals("object", output.type());
            assertTrue(output.properties().keySet().containsAll(
                    Set.of("stdout", "stderr", "exitCode", "timedOut", "truncated", "durationMs")));
        }

        @Test
        @DisplayName("подсказки: не read-only, деструктивный, не идемпотентный, внешний")
        void annotations() {
            ToolAnnotationsSpec annotations = handler.getTools().get(TOOL).annotations();

            assertFalse(annotations.readOnlyHint());
            assertTrue(annotations.destructiveHint());
            assertFalse(annotations.idempotentHint());
            assertTrue(annotations.openWorldHint());
        }

        @Test
        @DisplayName("бюджет ожидания результата — 1800 секунд")
        void awaitBudget() {
            assertEquals(1800, handler.getTools().get(TOOL).timeoutSeconds());
        }

        @Test
        @DisplayName("описание предупреждает о произвольной команде, отсутствии stdin и таймауте")
        void descriptionWarns() {
            String description = handler.getTools().get(TOOL).description().toLowerCase();

            assertTrue(description.contains("arbitrary"));
            assertTrue(description.contains("persistent shell"));
            assertTrue(description.contains("stdin"));
            assertTrue(description.contains("timeout"));
        }
    }

    @Nested
    @DisplayName("вызов")
    class Execution {

        @Test
        @DisplayName("команда уходит дословно, соединение и credentials — из ConnectorEnv")
        void passesCommandVerbatimAndEnv() {
            SshCommandResult expected = new SshCommandResult("out", "err", 0, false, false, 12L);
            when(executor.execute(eq(CONNECTION_ID), any(SshConnectionConfig.class), any(SshCommand.class)))
                    .thenReturn(expected);

            Map<String, Object> result = handler.executeTool(env(), TOOL, args("  uptime -p  ", 120));

            verify(executor).execute(eq(CONNECTION_ID),
                    argThat((SshConnectionConfig config) -> "example.com".equals(config.host())
                            && "root".equals(config.username())
                            && PRIVATE_KEY.equals(config.privateKey())),
                    argThat((SshCommand command) -> "  uptime -p  ".equals(command.command())
                            && Duration.ofSeconds(120).equals(command.timeout())));
            assertEquals("out", result.get("stdout"));
            assertEquals("err", result.get("stderr"));
            assertEquals(0, result.get("exitCode"));
            assertEquals(Boolean.FALSE, result.get("timedOut"));
        }

        @Test
        @DisplayName("без таймаута в аргументах команда получает 60 секунд")
        void defaultTimeout() {
            when(executor.execute(any(), any(SshConnectionConfig.class), any(SshCommand.class)))
                    .thenReturn(new SshCommandResult("", "", 0, false, false, 1L));

            handler.executeTool(env(), TOOL, args("id", null));

            verify(executor).execute(eq(CONNECTION_ID), any(SshConnectionConfig.class),
                    argThat((SshCommand command) -> Duration.ofSeconds(60).equals(command.timeout())));
        }

        @Test
        @DisplayName("ненулевой код возврата остаётся результатом, а не исключением")
        void nonZeroExitIsAResult() {
            when(executor.execute(any(), any(SshConnectionConfig.class), any(SshCommand.class)))
                    .thenReturn(new SshCommandResult("", "nope", 3, false, false, 5L));

            Map<String, Object> result = handler.executeTool(env(), TOOL, args("false", null));

            assertEquals(3, result.get("exitCode"));
            assertEquals("nope", result.get("stderr"));
        }

        @Test
        @DisplayName("пустая команда отклоняется до обращения к executor")
        void blankCommandRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), TOOL, args("  ", null)));

            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("некорректный таймаут отклоняется до обращения к executor")
        void invalidTimeoutRejected() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), TOOL, args("id", 0)));

            verifyNoInteractions(executor);
        }

        @Test
        @DisplayName("неизвестный тул отклоняется без обращения к executor")
        void unknownTool() {
            assertThrows(ConnectorException.class, () -> handler.executeTool(env(), "other_tool", Map.of()));

            verifyNoInteractions(executor);
        }
    }
}
