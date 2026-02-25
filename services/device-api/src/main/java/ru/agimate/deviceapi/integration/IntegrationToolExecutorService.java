package ru.agimate.deviceapi.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.database.entities.Integration;
import ru.agimate.deviceapi.database.repositories.IntegrationRepository;
import ru.agimate.deviceapi.service.CentrifugoService;
import ru.agimate.deviceapi.service.IToolUse;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationToolExecutorService {

    private final IntegrationPlatformRegistry platformRegistry;
    private final IntegrationRepository integrationRepository;
    private final CentrifugoService centrifugoService;
    private final ToolUseLogService toolUseLogService;
    private final IntegrationEncryptionService encryptionService;

    @Async
    public void execute(Integration integration, IToolUse toolUse, String agentId) {
        var handler = platformRegistry.getHandler(integration.getPlatform().getCode());
        Map<String, String> credentials = encryptionService.decryptCredentials(integration.getEncryptedData());

        try {
            Map<String, Object> result = handler.executeTool(
                    integration, credentials, toolUse.getName(), toolUse.getParams());

            // Update last used timestamp
            integrationRepository.updateLastUsedAt(integration.getId(), LocalDateTime.now());

            // Record result in log
            toolUseLogService.recordResult(
                    integration.getConnector(),
                    toolUse.getId(),
                    result.toString(),
                    null
            );

            // Push result back to agent
            if (agentId != null) {
                var toolResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(), result);
                centrifugoService.publishMessage("agent:" + agentId, toolResult);
            }

            log.debug("Executed tool '{}' for integration {}", toolUse.getName(), integration.getPubId());
        } catch (Exception e) {
            log.error("Failed to execute tool '{}' for integration {}: {}",
                    toolUse.getName(), integration.getPubId(), e.getMessage());

            // Record error (sanitized to avoid leaking tokens)
            try {
                toolUseLogService.recordResult(
                        integration.getConnector(),
                        toolUse.getId(),
                        null,
                        sanitizeErrorMessage(e.getMessage())
                );
            } catch (Exception logError) {
                log.warn("Failed to log tool error: {}", logError.getMessage());
            }

            // Push generic error to agent (no internal details)
            if (agentId != null) {
                var errorResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(),
                        Map.of("error", "Tool execution failed"));
                centrifugoService.publishMessage("agent:" + agentId, errorResult);
            }
        }
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null) return "Unknown error";
        return message.replaceAll("/bot[^/]+/", "/bot****/");
    }
}
