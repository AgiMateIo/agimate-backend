package ru.agimate.controlapi.connectors.core;

import ru.agimate.controlapi.connectors.core.annotation.Job;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.JobSpecification;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpecification;

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

    default Map<String, TriggerSpecification> getTriggers() {
        return Map.of();
    }

    Map<String, ConnectorToolSpec> getTools();

    default Map<String, JobSpecification> getJobs() {
        return Map.of();
    }

    Map<String, Object> executeTool(ConnectorContext context, String toolName, Map<String, Object> args);

    Map<String, Object> executeJob(ConnectorContext context, String name, Map<String, Object> args);
}
