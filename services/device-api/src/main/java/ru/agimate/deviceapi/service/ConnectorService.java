package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.connectors.integrations.IntegrationToolExecutorService;
import ru.agimate.deviceapi.connectors.internal.ServerToolExecutorService;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.service.dto.IToolUse;

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

    public void pushToConnector(UUID userPubId, String agentId, IToolUse toolUse) {
        Connector connector = connectorRepository.findById(toolUse.getConnectorCode())
                .orElseThrow(() -> new NotFoundStatusException("Connector not found: " + toolUse.getConnectorCode()));

        switch (connector.getType()) {
            case APP -> {
                var app = appRepository.findByPubIdAndUserPubIdNotDeleted(UUID.fromString(toolUse.getIdentity()), userPubId)
                        .orElseThrow(() -> new NotFoundStatusException("App not found: " + toolUse.getIdentity()));
                centrifugoService.publishMessage("device:" + app.getDeviceId(), toolUse);
            }
            case INTEGRATION -> {
                var credentials = integrationCredentialsRepository.findByPubIdAndUserPubIdNotDeleted(UUID.fromString(toolUse.getIdentity()), userPubId)
                        .orElseThrow(() -> new NotFoundStatusException("Integration credentials not found: " + toolUse.getIdentity()));
                integrationToolExecutorService.execute(credentials, toolUse, agentId);
            }
            case INTERNAL_SERVICE -> serverToolExecutorService.execute(toolUse, UUID.fromString(agentId), userPubId);
            case LOOPBACK -> log.warn("LOOPBACK connector called, ignoring. connectorCode={}, toolUse={}", toolUse.getConnectorCode(), toolUse.getName());
        }
    }

}
