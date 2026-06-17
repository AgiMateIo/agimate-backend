package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.channel.ChannelOutboundDispatcher;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Код-handler для Telegram-каналов: сводит все типы входящих сообщений к единому
 * {@link InboundMessage} и отправляет текстовый ответ через {@code telegram.send_message}.
 *
 * <p>Текущий этап: файлы (фото/документы) НЕ скачиваются и не обрабатываются — вместо контента
 * во входящий текст подставляется описание («пользователь отправил изображение/документ») плюс
 * подпись, если она есть. Скачивание/транскрибация — отдельный этап (мультимодальность).
 */
@Component
@RequiredArgsConstructor
public class TelegramChannelHandler implements ChannelHandler {

    public static final String NAME = "telegram";
    private static final String CONNECTOR_CODE = "telegram";

    private static final String TRIGGER_MESSAGE = "telegram.message_received";
    private static final String TRIGGER_PHOTO = "telegram.photo_received";
    private static final String TRIGGER_DOCUMENT = "telegram.document_received";
    private static final String TRIGGER_COMMAND = "telegram.command_received";
    private static final String TRIGGER_CALLBACK = "telegram.callback_query";
    private static final String TOOL_SEND_MESSAGE = "telegram.send_message";

    private final ChannelOutboundDispatcher dispatcher;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<TriggerDefinition> listOfTriggers(ChannelConfig config) {
        return List.of(
                new TriggerDefinition(TRIGGER_MESSAGE),
                new TriggerDefinition(TRIGGER_PHOTO),
                new TriggerDefinition(TRIGGER_DOCUMENT),
                new TriggerDefinition(TRIGGER_COMMAND),
                new TriggerDefinition(TRIGGER_CALLBACK));
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of(new ToolDefinition(config.connectorCode(), config.identity(), TOOL_SEND_MESSAGE));
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (!CONNECTOR_CODE.equals(config.connectorCode())) {
            throw new ConnectorException("telegram channel handler requires connectorCode='telegram'");
        }
    }

    @Override
    public Optional<InboundMessage> convert(ChannelConfig config, Trigger trigger) {
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        String text = switch (trigger.name()) {
            case TRIGGER_MESSAGE, TRIGGER_COMMAND -> asString(data.get("text"));
            case TRIGGER_CALLBACK -> "[Нажата кнопка] " + asString(data.get("data"));
            case TRIGGER_PHOTO -> withCaption("[Пользователь отправил изображение]", data.get("caption"));
            case TRIGGER_DOCUMENT -> withCaption(documentDescription(data.get("document")), data.get("caption"));
            default -> "[Получено сообщение неподдерживаемого типа: " + trigger.name() + "]";
        };
        String conversationKey = data.get("chatId") != null ? data.get("chatId").toString() : null;
        return Optional.of(InboundMessage.text(text, data, conversationKey));
    }

    @Override
    public void process(ChannelConfig config, OutboundMessage outbound, ChannelOutboundContext ctx) {
        Map<String, Object> replyContext = outbound.replyContext() != null ? outbound.replyContext() : Map.of();
        Object chatId = replyContext.get("chatId");
        if (chatId == null) {
            throw new ConnectorException("cannot send Telegram reply: chatId is missing in reply context");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chatId", chatId.toString());
        args.put("text", outbound.text());
        dispatcher.dispatch(ctx.agentId(), ctx.userId(),
                config.connectorCode(), config.identity(), TOOL_SEND_MESSAGE, args, ctx.toolCallId());
    }

    @SuppressWarnings("unchecked")
    private static String documentDescription(Object document) {
        if (document instanceof Map<?, ?> map) {
            Object fileName = ((Map<String, Object>) map).get("file_name");
            if (fileName != null) {
                return "[Пользователь отправил документ: " + fileName + "]";
            }
        }
        return "[Пользователь отправил документ]";
    }

    private static String withCaption(String description, Object caption) {
        String c = asString(caption);
        return c != null && !c.isBlank() ? description + " " + c : description;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }
}
