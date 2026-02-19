package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.repositories.AgentToolRepository;
import ru.agimate.deviceapi.database.repositories.AppRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseAuthorizerService {

    private final AgentToolRepository agentToolRepository;
    private final AppRepository appRepository;

    public void authorizeToolUseRequest(ApiKeyPrincipal apiKeyPrincipal, String appId, String toolName) {
        validateDeviceAccess(UUID.fromString(apiKeyPrincipal.userPubId()), appId);
        validateToolAuthorized(UUID.fromString(apiKeyPrincipal.pubId()), toolName);
    }

    private void validateDeviceAccess(UUID userPubId, String appId) {
        App app = appRepository.findByPubIdNotDeleted(UUID.fromString(appId))
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        if (!app.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("App is not accessible for this agent");
        }
    }

    private void validateToolAuthorized(UUID apiKeyPubId, String toolName) {
        if (!agentToolRepository.existsByApiKeyPubIdAndToolName(apiKeyPubId, toolName)) {
            throw new ForbiddenStatusException("Tool '" + toolName + "' is not authorized for this agent");
        }
    }
}
