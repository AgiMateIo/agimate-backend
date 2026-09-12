package ru.agimate.controlapi.connectors.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.enums.ConnectorJobType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConnectorRegistry")
class ConnectorRegistryTest {

    /** Identity + одна capability: коннектор только с тулами. */
    static class ToolOnlyHandler implements ConnectorHandler, ToolProvider {
        @Override
        public String connectorCode() {
            return "tool-only";
        }

        @Override
        public Map<String, ConnectorToolSpec> getTools() {
            return Map.of();
        }

        @Override
        public Map<String, Object> executeTool(ConnectorEnv env, String toolName, Map<String, Object> args) {
            return Map.of();
        }
    }

    /** Identity без capability-интерфейсов (как webchat до триггеров). */
    static class BareHandler implements ConnectorHandler {
        @Override
        public String connectorCode() {
            return "bare";
        }
    }

    /** Джобы зависят от инстанса: имя джобы — connectionId, пришедший в env. */
    static class InstanceJobHandler implements ConnectorHandler, JobProvider {
        @Override
        public String connectorCode() {
            return "jobs";
        }

        @Override
        public Map<String, JobSpec> getJobs() {
            return Map.of();
        }

        @Override
        public Map<String, JobSpec> getJobs(ConnectorEnv env) {
            return Map.of(env.connectionId(), new JobSpec(
                    env.connectionId(), ConnectorJobType.PERIODIC, Map.of(), Map.of(), 60));
        }

        @Override
        public Map<String, Object> executeJob(ConnectorEnv env, String name, Map<String, Object> args) {
            return Map.of();
        }
    }

    private final ToolOnlyHandler toolOnly = new ToolOnlyHandler();
    private final ConnectorRegistry registry = new ConnectorRegistry(
            List.of(toolOnly, new BareHandler(), new InstanceJobHandler()));

    @Test
    @DisplayName("capability(): каст готового handler'а к его capability")
    void capabilityReturns() {
        assertSame(toolOnly, ConnectorRegistry.capability(toolOnly, ToolProvider.class));
    }

    @Test
    @DisplayName("capability(): handler без capability → ConnectorException")
    void capabilityMissing() {
        ConnectorException e = assertThrows(ConnectorException.class,
                () -> ConnectorRegistry.capability(toolOnly, JobProvider.class));
        assertTrue(e.getMessage().contains("does not support JobProvider"));
    }

    @Test
    @DisplayName("getHandler: неизвестный коннектор → ConnectorException")
    void getHandlerUnknownConnector() {
        assertThrows(ConnectorException.class, () -> registry.getHandler("unknown"));
    }

    @Test
    @DisplayName("findCapability: Optional.empty без capability или без коннектора")
    void findCapability() {
        assertEquals(toolOnly, registry.findCapability("tool-only", ToolProvider.class).orElseThrow());
        assertTrue(registry.findCapability("bare", ToolProvider.class).isEmpty());
        assertTrue(registry.findCapability("tool-only", JobProvider.class).isEmpty());
        assertTrue(registry.findCapability("unknown", ToolProvider.class).isEmpty());
    }

    @Test
    @DisplayName("declaredJobs: декларация инстанса; пустая карта без JobProvider, empty без коннектора")
    void declaredJobs() {
        String connectionId = UUID.randomUUID().toString();

        assertEquals(Set.of(connectionId), registry.declaredJobs("jobs", connectionId).orElseThrow().keySet());
        assertTrue(registry.declaredJobs("tool-only", connectionId).orElseThrow().isEmpty());
        assertTrue(registry.declaredJobs("unknown", connectionId).isEmpty());
    }
}
