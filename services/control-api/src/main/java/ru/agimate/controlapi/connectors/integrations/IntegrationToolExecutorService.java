package ru.agimate.controlapi.connectors.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.controller.app.dto.ToolResultRequest;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.ToolUseLogService;
import ru.agimate.controlapi.service.dto.ToolUsePayload;

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
    public void execute(IntegrationCredentials integrationCredentials, ToolUsePayload toolUse, UUID agentId) {
        var integrationHandler = integrationsRegistry.getHandler(integrationCredentials.getConnectorCode());

        try {
            Map<String, Object> result = integrationHandler.executeTool(
                    integrationCredentials, toolUse.name(), toolUse.input());

            integrationCredentialsRepository.updateLastUsedAt(integrationCredentials.getId(), LocalDateTime.now());

            var toolResult = new ToolResultRequest(
                    toolUse.id(), toolUse.connectorCode(), JsonUtils.writeValueAsString(result), null);
            toolUseLogService.recordOutput(toolResult);

            if (agentId != null) {
                agentDeliveryService.deliverToolResult(agentId, toolResult);
            }

            log.debug("Executed tool '{}' for integration {}", toolUse.name(), integrationCredentials.getId());
        } catch (Exception e) {
            log.error("Failed to execute tool '{}' for integration {}: {}",
                    toolUse.name(), integrationCredentials.getId(), e.getMessage());

            var errorResult = new ToolResultRequest(
                    toolUse.id(), toolUse.connectorCode(), null, "Tool execution failed");

            try {
                toolUseLogService.recordOutput(errorResult);
            } catch (Exception logError) {
                log.warn("Failed to log integration tool error: {}", logError.getMessage());
            }

            if (agentId != null) {
                agentDeliveryService.deliverToolResult(agentId, errorResult);
            }
        }
    }

}
