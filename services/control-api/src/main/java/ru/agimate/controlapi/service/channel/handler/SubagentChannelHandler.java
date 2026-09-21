package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.channel.handler.dto.TriggerDefinition;
import ru.agimate.controlapi.service.subagent.ChildOutput;
import ru.agimate.controlapi.service.subagent.SubagentService;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The subagents channel: a subagent's session is a conversation of this channel, so its runs get what
 * any conversation gets — history, the dialogue preset, steering of an added request. The other side
 * of the conversation is the agent that asked, not a person: the inbound is its request, the outbound
 * is the report owed to it.
 *
 * <p>The report is not delivered here. A handler has no side effects beyond its own channel — that
 * keeps it out of a bean cycle with the router — so the output is announced as {@link ChildOutput}
 * and {@code SubagentReportListener} turns it into a run of the conversation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubagentChannelHandler implements ChannelHandler {

    public static final String NAME = SubagentService.CONNECTOR_CODE;

    private static final String STREAM_ANSWER = "answer";
    private static final String STREAM_ERROR = "error";

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<TriggerDefinition> listOfTriggers(ChannelConfig config) {
        return List.of(new TriggerDefinition(SubagentService.REQUEST_TRIGGER));
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of();
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (!SubagentService.CONNECTOR_CODE.equals(config.connectorCode())) {
            throw new ConnectorException("subagents channel handler requires connectorCode='subagents'");
        }
    }

    /**
     * The request as the subagent reads it. Trusted text — our own agent wrote it through our own
     * tool — but its content may quote anything the agent has read, so the values are escaped and
     * cannot close the tags around them.
     */
    @Override
    public Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger) {
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        String instructions = string(data.get("instructions"));
        if (instructions.isBlank()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder("<subagent_request")
                .append(" subagent_id=\"").append(PromptEscaping.attribute(string(data.get("subagentId")))).append('"')
                .append(" title=\"").append(PromptEscaping.attribute(string(data.get("title")))).append('"')
                .append(" mode=\"").append(PromptEscaping.attribute(string(data.get("mode")))).append("\">\n");
        String context = string(data.get("context"));
        if (!context.isBlank()) {
            text.append("<context>\n").append(PromptEscaping.text(context)).append("\n</context>\n");
        }
        text.append("<instructions>\n").append(PromptEscaping.text(instructions)).append("\n</instructions>\n")
                .append("</subagent_request>");
        return Optional.of(InboundMessage.text(text.toString()));
    }

    @Override
    public List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                              OutboundDispatch dispatch) {
        String stream = dispatch.stream() != null ? dispatch.stream() : STREAM_ANSWER;
        if (!STREAM_ANSWER.equals(stream) && !STREAM_ERROR.equals(stream)) {
            return List.of();
        }
        if (dispatch.runId() == null) {
            log.warn("subagent output without a run (session {}) — nothing to report", dispatch.sessionId());
            return List.of();
        }
        eventPublisher.publishEvent(new ChildOutput(
                dispatch.runId(), STREAM_ERROR.equals(stream), outbound.text()));
        return List.of();
    }

    private static String string(Object value) {
        return value != null ? value.toString() : "";
    }
}
