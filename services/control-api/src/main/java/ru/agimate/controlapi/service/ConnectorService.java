package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.execution.ToolExecutionService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.entities.ToolUseLog;
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

    public void pushToConnector(ToolUseLog toolUseLog) {
        Connector connector = connectorRepository.findById(toolUseLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolUseLog.getConnectorCode()));

        switch (connector.getType()) {
            case APP -> {
                var app = appRepository.findByIdAndUserIdNotDeleted(UUID.fromString(toolUseLog.getIdentity()), toolUseLog.getUserId())
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + toolUseLog.getIdentity()));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), "toolUse", ToolUsePayload.from(toolUseLog));
            }
            case INTEGRATION, INTERNAL_SERVICE -> toolExecutionService.executeTool(toolUseLog);
            case LOOPBACK -> log.warn("LOOPBACK connector called, ignoring. connectorCode={}, toolUse={}",
                    toolUseLog.getConnectorCode(), toolUseLog.getToolName());
        }
    }

}
