package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.abac.AccessDecision;
import ru.agimate.deviceapi.abac.ToolPolicyEvaluatorService;
import ru.agimate.deviceapi.database.repositories.AppRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseAuthorizerService {

    private final AppRepository appRepository;
    private final ToolPolicyEvaluatorService toolPolicyEvaluatorService;

    public void authorizeToolUseRequest(ApiKeyPrincipal apiKeyPrincipal, String connectorId, String toolName) {
        UUID userPubId = UUID.fromString(apiKeyPrincipal.userPubId());
        UUID apiKeyPubId = UUID.fromString(apiKeyPrincipal.pubId());

        validateConnector(userPubId, connectorId);

        String connectorName = resolveConnectorName(connectorId);
        AccessDecision decision = toolPolicyEvaluatorService.evaluate(apiKeyPubId, connectorName, null, toolName);
        if (!decision.allowed()) {
            throw new ForbiddenStatusException("Tool '" + toolName + "' is not authorized for this agent: " + decision.reason());
        }
    }

    private void validateConnector(UUID userPubId, String connectorId) {
        if ("local".equals(connectorId)) {
            return;
        }
        if (!appRepository.existsByPubIdAndUserPubId(UUID.fromString(connectorId), userPubId)) {
            throw new ForbiddenStatusException("Connector is not accessible for this agent");
        }
    }

    private String resolveConnectorName(String connectorId) {
        if ("local".equals(connectorId)) {
            return "local";
        }
        return connectorId;
    }
}
