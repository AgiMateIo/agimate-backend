package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.internal.acp.AcpConnectorService;
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
 * Код-handler ACP-каналов: входящие приходят готовым текстом из WebSocket-эндпоинта {@code /acp}
 * (триггер {@code message_received}), исходящие доставляются без тулов — JSON-RPC фреймы в живое
 * соединение через {@link AcpSessionRegistry}. Маппинг вывода агента на ACP {@code session/update}:
 * THINKING → {@code agent_thought_chunk}, TOOL_CALL → {@code tool_call} (сразу completed —
 * пер-тул статусов у бэка нет), TEXT/answer → {@code agent_message_chunk}; answer дополнительно
 * завершает висящий {@code session/prompt}, error — завершает его JSON-RPC ошибкой.
 */
@Component
@RequiredArgsConstructor
public class AcpChannelHandler implements ChannelHandler {

    public static final String NAME = "acp";

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
        return List.of(new TriggerDefinition(AcpConnectorService.TRIGGER_MESSAGE_RECEIVED));
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of();
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (!AcpConnectorService.CONNECTOR_CODE.equals(config.connectorCode())) {
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
    public Optional<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                                  OutboundDispatch dispatch) {
        String stream = dispatch.stream();
        if (STREAM_ERROR.equals(stream)) {
            // Терминальный нотис рана (квота/лимит шагов/ошибка модели) — это сообщение агента
            // пользователю, а не сбой протокола: показываем текстом и штатно завершаем turn.
            // Ошибку session/prompt слать нельзя — реальные сбои сюда не доходят (обрыв control-api
            // не создаёт SaveMessage), а код -32000 в ACP = auth_required → Zed «Authentication Required».
            sessionRegistry.sendUpdate(dispatch.sessionId(),
                    contentUpdate("agent_message_chunk", outbound.text()));
            sessionRegistry.completePrompt(dispatch.sessionId(), AcpSessionRegistry.STOP_END_TURN);
            return Optional.empty();
        }
        if (STREAM_PROGRESS.equals(stream)) {
            sessionRegistry.sendUpdate(dispatch.sessionId(), progressUpdate(dispatch, outbound.text()));
            return Optional.empty();
        }
        // answer (или сообщение без роли — по контракту OutboundDispatch это answer)
        sessionRegistry.sendUpdate(dispatch.sessionId(), contentUpdate("agent_message_chunk", outbound.text()));
        sessionRegistry.completePrompt(dispatch.sessionId(), AcpSessionRegistry.STOP_END_TURN);
        return Optional.empty();
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
