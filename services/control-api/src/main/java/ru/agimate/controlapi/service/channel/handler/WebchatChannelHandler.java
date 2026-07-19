package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundDispatch;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.channel.handler.dto.ToolDefinition;
import ru.agimate.controlapi.service.channel.handler.dto.TriggerDefinition;
import ru.agimate.controlapi.service.channel.handler.MediaStubs;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.webchat.WebchatMessagePublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Код-handler webchat-каналов: входящие приходят готовым текстом из {@code /manage/webchat}
 * (триггер {@code message_received}), исходящие доставляются без тулов — строка UI-истории
 * ({@code webchat_messages}) + Centrifugo-событие через {@link WebchatMessagePublisher}.
 * Единственный handler с {@code deliverProgress=true}: промежуточный вывод агента стримится в чат.
 */
@Component
@RequiredArgsConstructor
public class WebchatChannelHandler implements ChannelHandler {

    public static final String NAME = "webchat";
    /** Код webchat-коннектора — единый источник истины (у канальных коннекторов совпадает с {@link #NAME}). */
    public static final String CONNECTOR_CODE = NAME;
    /** Триггер входящего сообщения из веб-чата — единый источник истины для коннектора и оркестратора. */
    public static final String TRIGGER_MESSAGE_RECEIVED = "message_received";

    private static final String STREAM_ANSWER = "answer";

    private final ChannelRepository channelRepository;
    private final WebchatMessagePublisher webchatMessagePublisher;

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
            throw new ConnectorException("webchat channel handler requires connectorCode='webchat'");
        }
    }

    @Override
    public Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger) {
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        List<Part> parts = parts(data.get("parts"));
        Object textRaw = data.get("text");
        String userText = textRaw != null ? textRaw.toString() : "";
        if (userText.isBlank() && parts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new InboundMessage(MediaStubs.withStubs(userText, parts), parts));
    }

    /** Вложения из data триггера ({@code [{type,fileId,mime,size}]}) — уже провалидированы при отправке. */
    @SuppressWarnings("unchecked")
    private static List<Part> parts(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Part> parts = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) map;
            Object fileId = m.get("fileId");
            if (fileId == null || fileId.toString().isBlank()) {
                continue;
            }
            String mime = m.get("mime") != null ? m.get("mime").toString() : null;
            String type = m.get("type") != null ? m.get("type").toString() : Part.typeForMime(mime);
            long size = m.get("size") instanceof Number n ? n.longValue() : 0L;
            parts.add(new Part(type, fileId.toString(), mime, size, Map.of()));
        }
        return parts;
    }

    @Override
    public boolean deliverProgress(ChannelConfig config) {
        return true;
    }

    /** Вложения ответа доставляются parts'ами webchat-сообщения (изображения рендерит фронт). */
    @Override
    public boolean supportsOutboundAttachments() {
        return true;
    }

    @Override
    public List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                                  OutboundDispatch dispatch) {
        Channel channel = channelRepository.findByIdAndDeletedAtIsNull(dispatch.channelId())
                .orElseThrow(() -> new ConnectorException("webchat channel not found: " + dispatch.channelId()));
        String stream = dispatch.stream() != null ? dispatch.stream() : STREAM_ANSWER;
        webchatMessagePublisher.record(
                channel.getUserId(), config.agentId(), channel.getId(), dispatch.sessionId(),
                WebchatMessageDirection.AGENT, stream, dispatch.messageId(), outbound.text(),
                outbound.parts());
        return List.of();
    }
}
