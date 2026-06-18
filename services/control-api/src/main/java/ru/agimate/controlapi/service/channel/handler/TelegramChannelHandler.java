package ru.agimate.controlapi.service.channel.handler;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.controller.agent.dto.ToolUseRequest;
import ru.agimate.controlapi.service.AgentToolUseService;
import ru.agimate.controlapi.service.channel.handler.dto.*;
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
public class TelegramChannelHandler implements ChannelHandler {

    public static final String NAME = "telegram";
    private static final String CONNECTOR_CODE = "telegram";

    private static final String TRIGGER_MESSAGE = "telegram.message_received";
    private static final String TRIGGER_PHOTO = "telegram.photo_received";
    private static final String TRIGGER_DOCUMENT = "telegram.document_received";
    private static final String TRIGGER_COMMAND = "telegram.command_received";
    private static final String TRIGGER_CALLBACK = "telegram.callback_query";
    private static final String TOOL_SEND_MESSAGE = "telegram.send_message";
    private static final String CFG_ALLOWED_CHAT_IDS = "allowedChatIds";
    private static final String CFG_DEFAULT_CHAT_ID = "defaultChatId";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> getConfigFields() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put(CFG_ALLOWED_CHAT_IDS, ConfigSchema.arrayProp("integer", "Разрешённые чаты",
                "Если задано — обрабатываются только сообщения из этих chat_id; пусто — из всех"));
        props.put(CFG_DEFAULT_CHAT_ID, ConfigSchema.prop("integer", "Чат по умолчанию",
                "chat_id для проактивных ответов, когда нет входящего в сессии (например по time.due)"));
        return ConfigSchema.schema(props);
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
    public Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger) {
        Map<String, Object> data = trigger.data() != null ? trigger.data() : Map.of();
        if (!chatAllowed(config, data.get("chatId"))) {
            return Optional.empty();
        }
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
    public void handleOutput(ChannelConfig config, OutboundMessage outbound, ChannelOutboundContext ctx,
                        AgentToolUseService toolUseService) {
        Map<String, Object> replyContext = outbound.replyContext() != null ? outbound.replyContext() : Map.of();
        // Адрес ответа: из входящего (replyContext) → дефолт из config (проактивные/не-канальные триггеры).
        Object chatId = replyContext.get("chatId");
        if (chatId == null) {
            chatId = config.setting(CFG_DEFAULT_CHAT_ID);
        }
        if (chatId == null) {
            throw new ConnectorException(
                    "cannot send Telegram reply: no chatId in reply context and no defaultChatId in config");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("chatId", chatId.toString());
        args.put("text", outbound.text());
        ToolUseRequest request = ToolUseRequest.builder()
                .id(ctx.toolCallId())
                .connectorCode(config.connectorCode())
                .identity(config.identity())
                .name(TOOL_SEND_MESSAGE)
                .input(args)
                .build();
        toolUseService.processToolUse(ctx.agentId(), request);
    }

    private static boolean chatAllowed(ChannelConfig config, Object chatId) {
        Object raw = config.setting(CFG_ALLOWED_CHAT_IDS);
        if (!(raw instanceof List<?> allowed) || allowed.isEmpty()) {
            return true;
        }
        if (chatId == null) {
            return false;
        }
        String want = chatId.toString();
        return allowed.stream()
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .anyMatch(want::equals);
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
