package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
import ru.agimate.deviceapi.database.repositories.AgentToolRepository;
import ru.agimate.deviceapi.database.repositories.DeviceAuthKeyRepository;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ToolUseAuthorizerService {

    private final AgentToolRepository agentToolRepository;
    private final DeviceAuthKeyRepository deviceAuthKeyRepository;

    public void authorizeToolUseRequest(ApiKeyPrincipal apiKeyPrincipal, String deviceAuthKeyId, String toolName) {
        validateDeviceAccess(UUID.fromString(apiKeyPrincipal.userPubId()), deviceAuthKeyId);
        validateToolAuthorized(UUID.fromString(apiKeyPrincipal.pubId()), toolName);
    }

    private void validateDeviceAccess(UUID userPubId, String deviceAuthKeyId) {
        DeviceAuthKey key = deviceAuthKeyRepository.findByPubIdNotDeleted(UUID.fromString(deviceAuthKeyId))
                .orElseThrow(() -> new NotFoundStatusException("Device auth key not found"));
        if (!key.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Device is not accessible for this agent");
        }
    }

    private void validateToolAuthorized(UUID apiKeyPubId, String toolName) {
        if (!agentToolRepository.existsByApiKeyPubIdAndToolName(apiKeyPubId, toolName)) {
            throw new ForbiddenStatusException("Tool '" + toolName + "' is not authorized for this agent");
        }
    }
}
