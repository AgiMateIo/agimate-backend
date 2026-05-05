package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.integrations.IntegrationToolExecutorService;
import ru.agimate.deviceapi.connectors.internal.ServerToolExecutorService;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.entities.ToolUseLog;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;
import ru.agimate.deviceapi.service.dto.ToolUsePayload;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorService {

    private final ConnectorRepository connectorRepository;

    private final AppRepository appRepository;

    private final CentrifugoService centrifugoService;

    private final IntegrationCredentialsRepository integrationCredentialsRepository;

    private final IntegrationToolExecutorService integrationToolExecutorService;

    private final ServerToolExecutorService serverToolExecutorService;

    public void pushToConnector(ToolUseLog toolUseLog) {
        Connector connector = connectorRepository.findById(toolUseLog.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolUseLog.getConnectorCode()));

        var payload = ToolUsePayload.from(toolUseLog);
        UUID userPubId = toolUseLog.getUserPubId();
        UUID agentPubId = toolUseLog.getAgentPubId();

        switch (connector.getType()) {
            case APP -> {
                var app = appRepository.findByPubIdAndUserPubIdNotDeleted(UUID.fromString(payload.identity()), userPubId)
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + payload.identity()));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), "toolUse", payload);
            }
            case INTEGRATION -> {
                var credentials = integrationCredentialsRepository.findByPubIdAndUserPubIdNotDeleted(UUID.fromString(payload.identity()), userPubId)
                        .orElseThrow(() -> new NotFoundStatusException("Integration credentials not found: " + payload.identity()));
                integrationToolExecutorService.execute(credentials, payload, agentPubId);
            }
            case INTERNAL_SERVICE -> serverToolExecutorService.execute(payload, agentPubId, userPubId);
            case LOOPBACK -> log.warn("LOOPBACK connector called, ignoring. connectorCode={}, toolUse={}", payload.connectorCode(), payload.name());
        }
    }

}
