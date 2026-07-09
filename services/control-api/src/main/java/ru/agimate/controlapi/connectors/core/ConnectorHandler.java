package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

import java.util.Map;

/**
 * Единый SPI коннектора — общий для internal- и integration-коннекторов.
 *
 * <p>Коннектор состоит из «фасада» (реализация этого интерфейса, обычно через
 * {@link BaseConnectorHandler}) и tool-сервиса с {@code @Tool}-методами, в которые фасад
 * делегирует выполнение. Тулы доступны LLM; таски ({@link Job}) исполняются
 * scheduler'ом из строк {@code connector_jobs}.
 */
public interface ConnectorHandler {

    String connectorCode();

    default String connectorName() {
        return connectorCode();
    }

    /**
     * Type-level capability-дескриптор (4 оси, см. {@link ConnectorCapabilities}). Источник истины —
     * код; бутстрап персистит в каталог {@code connectors}. Дефолт — internal (backend-исполнение,
     * статические тулы, приватный скоуп); коннекторы с иными осями переопределяют.
     */
    default ConnectorCapabilities capabilities() {
        return ConnectorCapabilities.internal();
    }

    default Map<String, TriggerSpec> getTriggers() {
        return Map.of();
    }

    Map<String, ConnectorToolSpec> getTools();

    /**
     * Спеки тулов для конкретного экземпляра коннектора. По умолчанию совпадают со
     * статическими {@link #getTools()} — большинство коннекторов не зависят от instance.
     * Динамические коннекторы (например MCP) переопределяют: набор тулов открывается в рантайме
     * per-connectionId, поэтому здесь возвращают список под {@code context.connectionId()} (для MCP —
     * из кэша {@code connection_tools}). Контекст несёт connectionId; расшифровка credentials для листинга
     * не требуется.
     */
    default Map<String, ConnectorToolSpec> getTools(ConnectorContext context) {
        return getTools();
    }

    default Map<String, JobSpec> getJobs() {
        return Map.of();
    }

    Map<String, Object> executeTool(ConnectorContext context, String toolName, Map<String, Object> args);

    Map<String, Object> executeJob(ConnectorContext context, String name, Map<String, Object> args);
}
