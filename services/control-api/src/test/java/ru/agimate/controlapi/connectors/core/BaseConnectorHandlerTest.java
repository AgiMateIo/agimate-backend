package ru.agimate.controlapi.connectors.core;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;

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
            "identity-1", UUID.randomUUID(), UUID.randomUUID(), Map.of("token", "secret"), null);

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
        @DisplayName("возвращает @Tool-методы без @TaskOnly")
        void exposesPlainTools() {
            Map<String, ToolSpecification> tools = handler.getTools();

            assertTrue(tools.containsKey("test.echo"));
            assertTrue(tools.containsKey("test.fail"));
            assertEquals("Echo text back", tools.get("test.echo").description());
        }

        @Test
        @DisplayName("исключает @TaskOnly-методы")
        void excludesTaskOnlyMethods() {
            Map<String, ToolSpecification> tools = handler.getTools();

            assertFalse(tools.containsKey("test.periodic_task"));
            assertFalse(tools.containsKey("test.cron_task"));
        }
    }

    @Nested
    @DisplayName("getTasks")
    class GetTasks {

        @Test
        @DisplayName("строит PERIODIC-спеку из атрибутов аннотации")
        void buildsPeriodicSpecification() {
            TaskSpecification spec = handler.getTasks().get("test.periodic_task");

            assertNotNull(spec);
            assertEquals(ConnectorTaskType.PERIODIC, spec.taskType());
            assertEquals(5L, spec.taskConfig().get("intervalSeconds"));
            assertEquals(60, spec.timeoutSeconds());
            assertTrue(spec.taskArgs().isEmpty());
        }

        @Test
        @DisplayName("строит CRON-спеку из атрибутов аннотации")
        void buildsCronSpecification() {
            TaskSpecification spec = handler.getTasks().get("test.cron_task");

            assertNotNull(spec);
            assertEquals(ConnectorTaskType.CRON, spec.taskType());
            assertEquals("0 0 * * * *", spec.taskConfig().get("cron"));
            assertEquals("Europe/Moscow", spec.taskConfig().get("zone"));
            assertEquals(120, spec.timeoutSeconds());
        }

        @Test
        @DisplayName("не содержит обычных тулов")
        void excludesPlainTools() {
            assertFalse(handler.getTasks().containsKey("test.echo"));
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
        @DisplayName("отклоняет @TaskOnly-метод")
        void rejectsTaskOnlyMethod() {
            assertThrows(ConnectorException.class,
                    () -> handler.executeTool(CONTEXT, "test.periodic_task", Map.of()));
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
    @DisplayName("executeTask")
    class ExecuteTask {

        @Test
        @DisplayName("вызывает @TaskOnly-метод, void нормализуется в пустую мапу")
        void invokesTaskOnlyMethod() {
            Map<String, Object> result = handler.executeTask(CONTEXT, "test.periodic_task", Map.of());

            assertTrue(result.isEmpty());
            assertEquals(1, toolService.periodicRuns);
            assertEquals(CONTEXT, toolService.observedContext);
        }

        @Test
        @DisplayName("fallback: таска может вызвать обычную тулу")
        void fallsBackToPlainTool() {
            Map<String, Object> result = handler.executeTask(CONTEXT, "test.echo", Map.of("text", "scheduled"));

            assertEquals("scheduled", result.get("text"));
        }

        @Test
        @DisplayName("отклоняет неизвестную таску")
        void rejectsUnknownTask() {
            assertThrows(ConnectorException.class,
                    () -> handler.executeTask(CONTEXT, "test.unknown", Map.of()));
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

        @Tool(name = "test.echo", value = "Echo text back")
        public Map<String, Object> echo(@P("Text") String text, @P("Count") String count) {
            observedContext = ConnectorContextHolder.current();
            Map<String, Object> result = new HashMap<>();
            result.put("text", text);
            result.put("count", count);
            return result;
        }

        @Tool(name = "test.fail", value = "Always fails")
        public Map<String, Object> fail() {
            throw new IllegalStateException("boom");
        }

        @Tool(name = "test.periodic_task", value = "Periodic background task")
        @TaskOnly(intervalSeconds = 5, timeoutSeconds = 60)
        public void periodicTask() {
            observedContext = ConnectorContextHolder.current();
            periodicRuns++;
        }

        @Tool(name = "test.cron_task", value = "Cron background task")
        @TaskOnly(type = ConnectorTaskType.CRON, cron = "0 0 * * * *", zone = "Europe/Moscow", timeoutSeconds = 120)
        public void cronTask() {
        }
    }
}
