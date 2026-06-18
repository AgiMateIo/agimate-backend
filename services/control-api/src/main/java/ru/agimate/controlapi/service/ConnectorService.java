package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.execution.ToolExecutionService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.ToolCallLog;
import ru.agimate.controlapi.database.repositories.AppRepository;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.dto.ToolUsePayload;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    private final ConnectorRepository connectorRepository;

    private final AppRepository appRepository;

    private final CentrifugoService centrifugoService;

    private final ToolExecutionService toolExecutionService;

    public void pushToConnector(ToolCallLog toolCallLog) {
        Connector connector = connectorRepository.findById(toolCallLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolCallLog.getConnectorCode()));

        switch (connector.getType()) {
            case APP -> {
                var app = appRepository.findByIdAndUserIdNotDeleted(UUID.fromString(toolCallLog.getIdentity()), toolCallLog.getUserId())
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + toolCallLog.getIdentity()));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), "toolUse", ToolUsePayload.from(toolCallLog));
            }
            case INTEGRATION, INTERNAL_SERVICE -> toolExecutionService.executeTool(toolCallLog);
            case LOOPBACK -> log.warn("LOOPBACK connector called, ignoring. connectorCode={}, toolUse={}",
                    toolCallLog.getConnectorCode(), toolCallLog.getName());
        }
    }

}
