package ru.agimate.deviceapi.connectors.integrations;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.database.entities.IntegrationCredentials;
import ru.agimate.deviceapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;
import ru.agimate.deviceapi.service.dto.IToolUse;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationToolExecutorService {

    private final IntegrationsRegistry integrationsRegistry;
    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final CentrifugoService centrifugoService;

    @Async
    public void execute(IntegrationCredentials integrationCredentials, IToolUse toolUse, String agentId) {
        var integrationHandler = integrationsRegistry.getHandler(integrationCredentials.getConnectorCode());

        try {
            Map<String, Object> result = integrationHandler.executeTool(
                    integrationCredentials, toolUse.getName(), toolUse.getInput());

            // Update last used timestamp
            integrationCredentialsRepository.updateLastUsedAt(integrationCredentials.getId(), LocalDateTime.now());

            // todo: use router like trigger router, because it can be agent with webhook
            // Push result back to agent
            if (agentId != null) {
                var toolResult = new ToolResultRequest(
                        toolUse.getId(), toolUse.getConnectorCode(), JsonUtils.writeValueAsString(result), null);
                centrifugoService.publishMessage("agent:" + agentId, "toolResult", toolResult);
            }

            log.debug("Executed tool '{}' for integration {}", toolUse.getName(), integrationCredentials.getPubId());
        } catch (Exception e) {
            log.error("Failed to execute tool '{}' for integration {}: {}",
                    toolUse.getName(), integrationCredentials.getPubId(), e.getMessage());

            // Push generic error to agent (no internal details)
            if (agentId != null) {
                var errorResult = new ToolResultRequest(
                        toolUse.getId(), toolUse.getConnectorCode(), null, "Tool execution failed");
                centrifugoService.publishMessage("agent:" + agentId, "toolResult", errorResult);
            }
        }
    }

}
