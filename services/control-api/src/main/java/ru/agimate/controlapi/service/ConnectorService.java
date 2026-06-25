package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.enums.ConnectorType;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.ToolCallPayload;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    private final ConnectorRepository connectorRepository;

    private final ConnectionRepository connectionRepository;

    private final AppRepository appRepository;

    private final CentrifugoService centrifugoService;

    private final ToolExecutionService toolExecutionService;

    public void pushToConnector(ToolCallLog toolCallLog) {
        Connector connector = connectorRepository.findById(toolCallLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolCallLog.getConnectorCode()));

        // Роутинг по execution locus: BACKEND — исполняем in-proc; EXTERNAL — пушим на устройство;
        // AGENT — исполняет агент, control-api лишь авторизует (молчаливо игнорируем доставку).
        switch (resolveLocus(connector)) {
            case BACKEND -> toolExecutionService.executeTool(toolCallLog);
            case EXTERNAL -> {
                // identity = connections.id; устройство берём по connection.app_id.
                Connection connection = connectionRepository
                        .findByIdNotDeleted(UUID.fromString(toolCallLog.getIdentity()))
                        .orElseThrow(() -> new NotFoundStatusException("Connection not found: " + toolCallLog.getIdentity()));
                var app = appRepository.findByIdAndUserIdNotDeleted(connection.getAppId(), toolCallLog.getUserId())
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + connection.getAppId()));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), "toolCall", ToolCallPayload.from(toolCallLog));
            }
            case AGENT -> log.warn("AGENT-locus connector called, ignoring. connectorCode={}, toolCall={}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
        }
    }

    /** Execution locus из capabilities; null-safe fallback по типу для строк до бэкфилла capabilities. */
    private static ExecutionLocus resolveLocus(Connector connector) {
        if (connector.getCapabilities() != null && connector.getCapabilities().executionLocus() != null) {
            return connector.getCapabilities().executionLocus();
        }
        ConnectorType type = connector.getType();
        return switch (type) {
            case APP -> ExecutionLocus.EXTERNAL;
            case LOOPBACK -> ExecutionLocus.AGENT;
            case INTEGRATION, INTERNAL_SERVICE -> ExecutionLocus.BACKEND;
        };
    }

}
