package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.database.repositories.AgentToolRepository;
import ru.agimate.deviceapi.database.repositories.ConnectorRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseAuthorizerService {

    private final AgentToolRepository agentToolRepository;
    private final ConnectorRepository connectorRepository;

    public void authorizeToolUseRequest(ApiKeyPrincipal apiKeyPrincipal, String connectorId, String toolName) {
        validateConnector(UUID.fromString(apiKeyPrincipal.userPubId()), connectorId);
        validateToolAuthorized(UUID.fromString(apiKeyPrincipal.pubId()), toolName);
    }

    private void validateConnector(UUID userPubId, String connectorId) {
        if ("local".equals(connectorId)) {
            return;
        }
        if (!connectorRepository.existsByPubIdAndUserPubId(UUID.fromString(connectorId), userPubId)) {
            throw new ForbiddenStatusException("Connector is not accessible for this agent");
        }
    }

    private void validateToolAuthorized(UUID apiKeyPubId, String toolName) {
        if (!agentToolRepository.existsByApiKeyPubIdAndToolName(apiKeyPubId, toolName)) {
            throw new ForbiddenStatusException("Tool '" + toolName + "' is not authorized for this agent");
        }
    }
}
