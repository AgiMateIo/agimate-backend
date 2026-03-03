package ru.agimate.deviceapi.service.servertools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.service.CentrifugoService;
import ru.agimate.deviceapi.service.IToolUse;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerToolExecutorService {

    private final ServerSideToolRegistry toolRegistry;
    private final CentrifugoService centrifugoService;
    private final ToolUseLogService toolUseLogService;

    @Async
    public void execute(Connector connector, IToolUse toolUse, String agentId) {
        var handler = toolRegistry.getHandlerByToolName(toolUse.getName());

        try {
            Map<String, Object> result = handler.executeTool(
                    toolUse.getName(),
                    toolUse.getParams(),
                    UUID.fromString(agentId),
                    connector.getUserPubId()
            );

            toolUseLogService.recordResult(connector, toolUse.getId(), result.toString(), null);

            if (agentId != null) {
                var toolResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(), result);
                centrifugoService.publishMessage("agent:" + agentId, toolResult);
            }

            log.debug("Executed server tool '{}' for connector {}", toolUse.getName(), connector.getPubId());
        } catch (Exception e) {
            log.error("Failed to execute server tool '{}' for connector {}: {}",
                    toolUse.getName(), connector.getPubId(), e.getMessage());

            try {
                toolUseLogService.recordResult(connector, toolUse.getId(), null, e.getMessage());
            } catch (Exception logError) {
                log.warn("Failed to log server tool error: {}", logError.getMessage());
            }

            if (agentId != null) {
                var errorResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(),
                        Map.of("error", "Tool execution failed"));
                centrifugoService.publishMessage("agent:" + agentId, errorResult);
            }
        }
    }
}
