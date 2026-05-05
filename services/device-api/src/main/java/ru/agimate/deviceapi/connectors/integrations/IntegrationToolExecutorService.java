package ru.agimate.deviceapi.connectors.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.service.AgentDeliveryService;
import ru.agimate.deviceapi.service.ToolUseLogService;
import ru.agimate.deviceapi.service.dto.ToolUsePayload;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationToolExecutorService {

    private final IntegrationsRegistry integrationsRegistry;
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final AgentDeliveryService agentDeliveryService;
    private final ToolUseLogService toolUseLogService;

    @Async
    public void execute(IntegrationCredentials integrationCredentials, ToolUsePayload toolUse, UUID agentPubId) {
        var integrationHandler = integrationsRegistry.getHandler(integrationCredentials.getConnectorCode());

        try {
            Map<String, Object> result = integrationHandler.executeTool(
                    integrationCredentials, toolUse.name(), toolUse.input());

            integrationCredentialsRepository.updateLastUsedAt(integrationCredentials.getId(), LocalDateTime.now());

            var toolResult = new ToolResultRequest(
                    toolUse.id(), toolUse.connectorCode(), JsonUtils.writeValueAsString(result), null);
            toolUseLogService.recordOutput(toolResult);

            if (agentPubId != null) {
                agentDeliveryService.deliverToolResult(agentPubId, toolResult);
            }

            log.debug("Executed tool '{}' for integration {}", toolUse.name(), integrationCredentials.getPubId());
        } catch (Exception e) {
            log.error("Failed to execute tool '{}' for integration {}: {}",
                    toolUse.name(), integrationCredentials.getPubId(), e.getMessage());

            var errorResult = new ToolResultRequest(
                    toolUse.id(), toolUse.connectorCode(), null, "Tool execution failed");

            try {
                toolUseLogService.recordOutput(errorResult);
            } catch (Exception logError) {
                log.warn("Failed to log integration tool error: {}", logError.getMessage());
            }

            if (agentPubId != null) {
                agentDeliveryService.deliverToolResult(agentPubId, errorResult);
            }
        }
    }

}
