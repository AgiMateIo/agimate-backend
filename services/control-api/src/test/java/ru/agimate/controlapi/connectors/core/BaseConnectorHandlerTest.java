package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JsonSchema;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BaseConnectorHandler")
class BaseConnectorHandlerTest {

    private static final ConnectorContext CONTEXT = new ConnectorContext(
            "identity-1", UUID.randomUUID(), UUID.randomUUID(), null, Map.of("token", "secret"), null);

    private TestToolService toolService;
    private TestConnectorService handler;

    @BeforeEach
    void setUp() {
        toolService = new TestToolService();
        handler = new TestConnectorService(toolService);
    }

    @Nested
    @DisplayName("getTools")
    class GetTools {

        @Test
        @DisplayName("возвращает @Tool-методы без @Job")
        void exposesPlainTools() {
            Map<String, ConnectorToolSpec> tools = handler.getTools();

            assertTrue(tools.containsKey("test.echo"));
            assertTrue(tools.containsKey("test.fail"));
            assertEquals("Echo text back", tools.get("test.echo").description());
        }

        @Test
        @DisplayName("исключает @Job(isJobOnly = true)-методы")
        void excludesTaskOnlyMethods() {
            Map<String, ConnectorToolSpec> tools = handler.getTools();

            assertFalse(tools.containsKey("test.periodic_task"));
            assertFalse(tools.containsKey("test.cron_task"));
        }

        @Test
        @DisplayName("включает @Job(isJobOnly = false)-метод как тулу")
        void exposesDualTaskAsTool() {
            Map<String, ConnectorToolSpec> tools = handler.getTools();

            assertTrue(tools.containsKey("test.dual_task"));
        }

        @Test
        @DisplayName("строит inputSchema/outputSchema рефлексией и дефолтные MCP-хинты")
        void buildsMcpSpec() {
            ConnectorToolSpec echo = handler.getTools().get("test.echo");

            assertEquals("Echo text back", echo.description());

            // inputSchema из параметров метода (имена через -parameters, описания из @ToolParam)
            assertEquals("object", echo.inputSchema().type());
            assertEquals("string", echo.inputSchema().properties().get("text").type());
            assertEquals("Text", echo.inputSchema().properties().get("text").description());
            assertTrue(echo.inputSchema().required().contains("text"));
            assertTrue(echo.inputSchema().required().contains("count"));

            // Map<String,Object> → object + additionalProperties = {} (any)
            assertEquals("object", echo.outputSchema().type());
            assertNotNull(echo.outputSchema().additionalProperties());

            // хинты не заданы → пессимистичные дефолты MCP
            assertFalse(echo.annotations().readOnlyHint());
            assertTrue(echo.annotations().destructiveHint());
        }

        @Test
        @DisplayName("у тула без параметров inputSchema — пустой object (MCP-требование)")
        void zeroArgToolHasObjectInputSchema() {
            ConnectorToolSpec fail = handler.getTools().get("test.fail");

            assertNotNull(fail.inputSchema());
            assertEquals("object", fail.inputSchema().type());
        }

        @Test
        @DisplayName("inputSchema типизированных параметров: integer и enum")
        void buildsTypedParamSchema() {
            JsonSchema schema = handler.getTools().get("test.typed").inputSchema();

            assertEquals("integer", schema.properties().get("n").type());
            assertEquals("string", schema.properties().get("kind").type());
            assertTrue(schema.properties().get("kind").enumValues().contains("CRON"));
        }
    }

    @Nested
    @DisplayName("getJobs")
    class GetTasks {

        @Test
        @DisplayName("строит PERIODIC-спеку из атрибутов аннотации")
        void buildsPeriodicSpecification() {
            JobSpec spec = handler.getJobs().get("test.periodic_task");

            assertNotNull(spec);
            assertEquals(ConnectorJobType.PERIODIC, spec.type());
            assertEquals(5L, spec.config().get("intervalSeconds"));
            assertEquals(60, spec.timeoutSeconds());
            assertTrue(spec.args().isEmpty());
        }

        @Test
        @DisplayName("строит CRON-спеку из атрибутов аннотации")
        void buildsCronSpecification() {
            JobSpec spec = handler.getJobs().get("test.cron_task");

            assertNotNull(spec);
            assertEquals(ConnectorJobType.CRON, spec.type());
            assertEquals("0 0 * * * *", spec.config().get("cron"));
            assertEquals("Europe/Moscow", spec.config().get("zone"));
            assertEquals(120, spec.timeoutSeconds());
        }

        @Test
        @DisplayName("не содержит обычных тулов")
        void excludesPlainTools() {
            assertFalse(handler.getJobs().containsKey("test.echo"));
        }

        @Test
        @DisplayName("содержит @Job(isJobOnly = false)-метод")
        void includesDualTaskTool() {
            JobSpec spec = handler.getJobs().get("test.dual_task");

            assertNotNull(spec);
            assertEquals(ConnectorJobType.PERIODIC, spec.type());
            assertEquals(10L, spec.config().get("intervalSeconds"));
        }
    }

    @Nested
    @DisplayName("executeTool")
    class ExecuteTool {

        @Test
        @DisplayName("вызывает метод и маппит аргументы по именам параметров")
        void invokesToolWithArgs() {
            Map<String, Object> result = handler.executeTool(CONTEXT, "test.echo",
                    Map.of("text", "hello", "count", 3));

            assertEquals("hello", result.get("text"));
            assertEquals("3", result.get("count")); // число сконвертировано в String-параметр
        }

        @Test
        @DisplayName("отклоняет неизвестную тулу")
        void rejectsUnknownTool() {
            assertThrows(ConnectorException.class,
                    () -> handler.executeTool(CONTEXT, "test.unknown", Map.of()));
        }

        @Test
        @DisplayName("биндит не-String параметры (int, enum) через Jackson")
        void bindsTypedArgs() {
            Map<String, Object> result = handler.executeTool(CONTEXT, "test.typed",
                    Map.of("n", 7, "kind", "CRON"));

            assertEquals(7, result.get("n"));
            assertEquals(ConnectorJobType.CRON, result.get("kind"));
        }

        @Test
        @DisplayName("отклоняет @Job(isJobOnly = true)-метод")
        void rejectsTaskOnlyMethod() {
            assertThrows(ConnectorException.class,
                    () -> handler.executeTool(CONTEXT, "test.periodic_task", Map.of()));
        }

        @Test
        @DisplayName("вызывает @Job(isJobOnly = false)-метод как тулу")
        void invokesDualTaskAsTool() {
            Map<String, Object> result = handler.executeTool(CONTEXT, "test.dual_task", Map.of());

            assertTrue(result.isEmpty());
            assertEquals(1, toolService.dualRuns);
        }

        @Test
        @DisplayName("пробрасывает RuntimeException из метода и чистит контекст")
        void propagatesRuntimeExceptionAndClearsContext() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> handler.executeTool(CONTEXT, "test.fail", Map.of()));

            assertEquals("boom", e.getMessage());
            assertThrows(ConnectorException.class, ConnectorContextHolder::current);
        }

        @Test
        @DisplayName("привязывает контекст на время вызова и чистит после")
        void bindsAndClearsContext() {
            handler.executeTool(CONTEXT, "test.echo", Map.of("text", "x"));

            assertEquals(CONTEXT, toolService.observedContext);
            assertThrows(ConnectorException.class, ConnectorContextHolder::current);
        }

        @Test
        @DisplayName("отсутствующие аргументы передаются как null")
        void missingArgsAreNull() {
            Map<String, Object> result = handler.executeTool(CONTEXT, "test.echo", Map.of("text", "x"));

            assertEquals("x", result.get("text"));
            assertNull(result.get("count"));
        }
    }

    @Nested
    @DisplayName("executeJob")
    class ExecuteTask {

        @Test
        @DisplayName("вызывает @Job-метод, void нормализуется в пустую мапу")
        void invokesTaskOnlyMethod() {
            Map<String, Object> result = handler.executeJob(CONTEXT, "test.periodic_task", Map.of());

            assertTrue(result.isEmpty());
            assertEquals(1, toolService.periodicRuns);
            assertEquals(CONTEXT, toolService.observedContext);
        }

        @Test
        @DisplayName("fallback: таска может вызвать обычную тулу")
        void fallsBackToPlainTool() {
            Map<String, Object> result = handler.executeJob(CONTEXT, "test.echo", Map.of("text", "scheduled"));

            assertEquals("scheduled", result.get("text"));
        }

        @Test
        @DisplayName("отклоняет неизвестную таску")
        void rejectsUnknownTask() {
            assertThrows(ConnectorException.class,
                    () -> handler.executeJob(CONTEXT, "test.unknown", Map.of()));
        }
    }

    static class TestConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

        TestConnectorService(TestToolService toolService) {
            super(toolService);
        }

        @Override
        public String connectorCode() {
            return "test";
        }
    }

    static class TestToolService {

        ConnectorContext observedContext;
        int periodicRuns;
        int dualRuns;

        @Tool(name = "test.echo", description = "Echo text back")
        public Map<String, Object> echo(@ToolParam("Text") String text, @ToolParam("Count") String count) {
            observedContext = ConnectorContextHolder.current();
            Map<String, Object> result = new HashMap<>();
            result.put("text", text);
            result.put("count", count);
            return result;
        }

        @Tool(name = "test.fail", description = "Always fails")
        public Map<String, Object> fail() {
            throw new IllegalStateException("boom");
        }

        @Tool(name = "test.periodic_task", description = "Periodic background task")
        @Job(intervalSeconds = 5, timeoutSeconds = 60)
        public void periodicTask() {
            observedContext = ConnectorContextHolder.current();
            periodicRuns++;
        }

        @Tool(name = "test.cron_task", description = "Cron background task")
        @Job(type = ConnectorJobType.CRON, cron = "0 0 * * * *", zone = "Europe/Moscow", timeoutSeconds = 120)
        public void cronTask() {
        }

        @Tool(name = "test.dual_task", description = "Both an LLM tool and a scheduled task")
        @Job(intervalSeconds = 10, isJobOnly = false)
        public void dualTask() {
            observedContext = ConnectorContextHolder.current();
            dualRuns++;
        }

        @Tool(name = "test.typed", description = "Non-String typed params")
        public Map<String, Object> typed(@ToolParam("Number") int n, @ToolParam("Kind") ConnectorJobType kind) {
            Map<String, Object> result = new HashMap<>();
            result.put("n", n);
            result.put("kind", kind);
            return result;
        }
    }
}
