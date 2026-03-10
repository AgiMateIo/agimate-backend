package ru.agimate.deviceapi.connectors.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.app.dto.ToolResultRequest;

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
    public void execute(IToolUse toolUse, UUID agentPubId, UUID userPubId) {
        var handler = toolRegistry.getHandlerByToolName(toolUse.getName());

        try {
            Map<String, Object> result = handler.executeTool(
                    toolUse.getName(),
                    toolUse.getInput(),
                    agentPubId,
                    userPubId
            );


            var toolResult = new ToolResultRequest(toolUse.getId(), toolUse.getConnectorCode(), JsonUtils.writeValueAsString(result), null);
            toolUseLogService.recordOutput(toolResult);
            centrifugoService.publishMessage("agent:" + agentPubId, toolResult);

            log.debug("Executed server tool '{}'", toolUse.getName());
        } catch (Exception e) {
            log.error("Failed to execute server tool '{}': {}", toolUse.getName(), e.getMessage());

            try {
                var toolResult = new ToolResultRequest(toolUse.getId(), toolUse.getName(), null, "Error");
                toolUseLogService.recordOutput(toolResult);
            } catch (Exception logError) {
                log.warn("Failed to log server tool error: {}", logError.getMessage());
            }

            var errorResult = new ToolResultRequest(
                    toolUse.getId(), toolUse.getName(), null,"Tool execution failed");
            centrifugoService.publishMessage("agent:" + agentPubId, errorResult);
        }
    }
}
