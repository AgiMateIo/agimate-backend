package ru.agimate.deviceapi.connectors.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.service.CentrifugoService;
import ru.agimate.deviceapi.service.IToolUse;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationToolExecutorService {

    private final IntegrationsRegistry integrationsRegistry;
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final CentrifugoService centrifugoService;
    private final IntegrationEncryptionService encryptionService;

    @Async
    public void execute(IntegrationCredentials integrationCredentials, IToolUse toolUse, String agentId) {
        var integrationHandler = integrationsRegistry.getHandler(integrationCredentials.extractPlatformCode());
        Map<String, String> credentials = encryptionService.decryptCredentials(integrationCredentials.getEncryptedData());

        try {
            Map<String, Object> result = integrationHandler.executeTool(
                    integrationCredentials, credentials, toolUse.getName(), toolUse.getInput());

            // Update last used timestamp
            integrationCredentialsRepository.updateLastUsedAt(integrationCredentials.getId(), LocalDateTime.now());

            // Push result back to agent
            if (agentId != null) {
                var toolResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(), result);
                centrifugoService.publishMessage("agent:" + agentId, toolResult);
            }

            log.debug("Executed tool '{}' for integration {}", toolUse.getName(), integrationCredentials.getPubId());
        } catch (Exception e) {
            log.error("Failed to execute tool '{}' for integration {}: {}",
                    toolUse.getName(), integrationCredentials.getPubId(), e.getMessage());

            // Push generic error to agent (no internal details)
            if (agentId != null) {
                var errorResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(),
                        Map.of("error", "Tool execution failed"));
                centrifugoService.publishMessage("agent:" + agentId, errorResult);
            }
        }
    }

}
