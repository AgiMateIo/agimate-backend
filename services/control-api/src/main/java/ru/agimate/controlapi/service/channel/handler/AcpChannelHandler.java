package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.acp.AcpSessionRegistry;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.channel.handler.dto.TriggerDefinition;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The code handler for ACP channels: inbound arrives as ready text from the WebSocket endpoint
 * {@code /acp} (the trigger {@code message_received}), and outbound is delivered without tools — as
 * JSON-RPC frames into the live connection, through {@link AcpSessionRegistry}. The agent's output
 * maps onto ACP {@code session/update} as follows: THINKING → {@code agent_thought_chunk}, TOOL_CALL
 * → {@code tool_call} (immediately completed — the backend has no per-tool statuses), TEXT/answer →
 * {@code agent_message_chunk}; an answer additionally completes the pending {@code session/prompt},
 * and an error completes it with a JSON-RPC error.
 */
@Component
@RequiredArgsConstructor
public class AcpChannelHandler implements ChannelHandler {

    public static final String NAME = "acp";
    /** Code of the ACP connector — the single source of truth (for channel connectors it equals {@link #NAME}). */
    public static final String CONNECTOR_CODE = NAME;
    /** Trigger for an incoming message from the IDE — the single source of truth for the connector and the orchestrator. */
    public static final String TRIGGER_MESSAGE_RECEIVED = "message_received";

    private static final String STREAM_PROGRESS = "progress";
    private static final String STREAM_ERROR = "error";
    private static final String PROGRESS_THINKING = "THINKING";
    private static final String PROGRESS_TOOL_CALL = "TOOL_CALL";

    private final AcpSessionRegistry sessionRegistry;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<TriggerDefinition> listOfTriggers(ChannelConfig config) {
        return List.of(new TriggerDefinition(TRIGGER_MESSAGE_RECEIVED));
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of();
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (!CONNECTOR_CODE.equals(config.connectorCode())) {
            throw new ConnectorException("acp channel handler requires connectorCode='acp'");
        }
    }

    @Override
    public Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger) {
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        Object text = data.get("text");
        if (text == null || text.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(InboundMessage.text(text.toString()));
    }

    @Override
    public boolean deliverProgress(ChannelConfig config) {
        return true;
    }

    @Override
    public boolean contributesPromptTools() {
        return true;
    }

    @Override
    public List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                                  OutboundDispatch dispatch) {
        String stream = dispatch.stream();
        if (STREAM_ERROR.equals(stream)) {
            // A run's terminal notice (a quota, a step limit, a model error) is a message from the agent to the
            // user rather than a protocol failure: we show it as text and complete the turn normally. Sending a
            // session/prompt error is not an option — real failures never reach here (a control-api disconnect
            // creates no SaveMessage), and the code -32000 in ACP means auth_required → Zed shows «Authentication
            // Required».
            sessionRegistry.sendUpdate(dispatch.sessionId(),
                    contentUpdate("agent_message_chunk", outbound.text()));
            sessionRegistry.completePrompt(dispatch.sessionId(), AcpSessionRegistry.STOP_END_TURN);
            return List.of();
        }
        if (STREAM_PROGRESS.equals(stream)) {
            sessionRegistry.sendUpdate(dispatch.sessionId(), progressUpdate(dispatch, outbound.text()));
            return List.of();
        }
        // answer (or a message with no role — by the OutboundDispatch contract that is an answer)
        sessionRegistry.sendUpdate(dispatch.sessionId(), contentUpdate("agent_message_chunk", outbound.text()));
        sessionRegistry.completePrompt(dispatch.sessionId(), AcpSessionRegistry.STOP_END_TURN);
        return List.of();
    }

    private static Map<String, Object> progressUpdate(OutboundDispatch dispatch, String text) {
        if (PROGRESS_THINKING.equals(dispatch.progressType())) {
            return contentUpdate("agent_thought_chunk", text);
        }
        if (PROGRESS_TOOL_CALL.equals(dispatch.progressType())) {
            return Map.of(
                    "sessionUpdate", "tool_call",
                    "toolCallId", dispatch.messageId(),
                    "title", text,
                    "status", "completed");
        }
        return contentUpdate("agent_message_chunk", text);
    }

    private static Map<String, Object> contentUpdate(String sessionUpdate, String text) {
        return Map.of(
                "sessionUpdate", sessionUpdate,
                "content", Map.of("type", "text", "text", text));
    }
}
