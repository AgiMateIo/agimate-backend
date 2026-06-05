package ru.agimate.deviceapi.connectors.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;
import ru.agimate.deviceapi.service.AgentDeliveryService;
import ru.agimate.deviceapi.service.dto.ToolUsePayload;
import ru.agimate.deviceapi.service.ToolUseLogService;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerToolExecutorService {

    private final ServerSideToolRegistry toolRegistry;
    private final AgentDeliveryService agentDeliveryService;
    private final ToolUseLogService toolUseLogService;

    @Async
    public void execute(ToolUsePayload toolUse, UUID agentId, UUID userId) {
        var handler = toolRegistry.getHandlerByToolName(toolUse.name());

        try {
            Map<String, Object> result = handler.executeTool(
                    toolUse.name(),
                    toolUse.input(),
                    agentId,
                    userId
            );

            var toolResult = new ToolResultRequest(toolUse.id(), toolUse.connectorCode(), JsonUtils.writeValueAsString(result), null);
            toolUseLogService.recordOutput(toolResult);
            agentDeliveryService.deliverToolResult(agentId, toolResult);

            log.debug("Executed server tool '{}'", toolUse.name());
        } catch (Exception e) {
            log.error("Failed to execute server tool '{}': {}", toolUse.name(), e.getMessage());

            var errorResult = new ToolResultRequest(
                    toolUse.id(), toolUse.connectorCode(), null, "Tool execution failed");

            try {
                toolUseLogService.recordOutput(errorResult);
            } catch (Exception logError) {
                log.warn("Failed to log server tool error: {}", logError.getMessage());
            }

            agentDeliveryService.deliverToolResult(agentId, errorResult);
        }
    }
}
