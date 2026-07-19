package ru.agimate.controlapi.service.channel.handler;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.handler.dto.*;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Код-handler для Telegram-каналов: сводит все типы входящих сообщений к единому
 * {@link InboundMessage} и отправляет ответ (текст + вложения) через {@code telegram.send_*}.
 *
 * <p>Входящие фото/документы скачиваются на ingest-границе ({@link ru.agimate.controlapi.connectors
 * .integrations.telegram.TelegramMediaService}) и приходят сюда готовыми {@code data.parts} —
 * handler маппит их в {@link InboundMessage#parts()} + текст-заглушку (image воркер подаёт в LLM).
 * Если parts нет (скачивание отключено/не удалось), подставляется описание + подпись, как раньше.
 */
@Component
public class TelegramChannelHandler implements ChannelHandler {

    public static final String NAME = "telegram";
    private static final String CONNECTOR_CODE = "telegram";

    private static final String TRIGGER_MESSAGE = "message_received";
    private static final String TRIGGER_PHOTO = "photo_received";
    private static final String TRIGGER_DOCUMENT = "document_received";
    private static final String TRIGGER_COMMAND = "command_received";
    private static final String TRIGGER_CALLBACK = "callback_query";
    private static final String TOOL_SEND_MESSAGE = "send_message";
    private static final String TOOL_SEND_PHOTO = "send_photo";
    private static final String TOOL_SEND_VIDEO = "send_video";
    private static final String TOOL_SEND_DOCUMENT = "send_document";
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
        // Всё, что может вернуть handleOutput: текст + вложения attach-конвенции.
        return List.of(
                new ToolDefinition(config.connectionId(), TOOL_SEND_MESSAGE),
                new ToolDefinition(config.connectionId(), TOOL_SEND_PHOTO),
                new ToolDefinition(config.connectionId(), TOOL_SEND_VIDEO),
                new ToolDefinition(config.connectionId(), TOOL_SEND_DOCUMENT));
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
        // Медиа скачано на ingest'е → data.parts; текст = подпись + заглушки (image → LLM видит).
        List<Part> parts = parts(data.get("parts"));
        if (!parts.isEmpty()) {
            String caption = asString(data.get("caption"));
            return Optional.of(new InboundMessage(MediaStubs.withStubs(caption, parts), parts));
        }
        String text = switch (trigger.name()) {
            case TRIGGER_MESSAGE, TRIGGER_COMMAND -> asString(data.get("text"));
            case TRIGGER_CALLBACK -> "[Нажата кнопка] " + asString(data.get("data"));
            case TRIGGER_PHOTO -> withCaption("[Пользователь отправил изображение]", data.get("caption"));
            case TRIGGER_DOCUMENT -> withCaption(documentDescription(data.get("document")), data.get("caption"));
            default -> "[Получено сообщение неподдерживаемого типа: " + trigger.name() + "]";
        };
        return Optional.of(InboundMessage.text(text));
    }

    /** Вложения из {@code data.parts} ({@code [{type,fileId,mime,size,name}]}), материализованные на ingest'е. */
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
            Map<String, Object> meta = m.get("name") != null ? Map.of("name", m.get("name")) : Map.of();
            parts.add(new Part(type, fileId.toString(), mime, size, meta));
        }
        return parts;
    }

    @Override
    public boolean supportsOutboundAttachments() {
        return true;
    }

    @Override
    public List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                              OutboundDispatch dispatch) {
        Map<String, Object> replyContext = dispatch.replyContext() != null ? dispatch.replyContext() : Map.of();
        // Адрес ответа: из входящего (replyContext) → дефолт из config (проактивные/не-канальные триггеры).
        Object chatId = replyContext.get("chatId");
        if (chatId == null) {
            chatId = config.setting(CFG_DEFAULT_CHAT_ID);
        }
        if (chatId == null) {
            throw new ConnectorException(
                    "cannot send Telegram reply: no chatId in reply context and no defaultChatId in config");
        }

        List<ToolCallRequest> requests = new ArrayList<>();
        if (outbound.text() != null && !outbound.text().isBlank()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("chatId", chatId.toString());
            args.put("text", outbound.text());
            requests.add(request(config, dispatch.messageId(), TOOL_SEND_MESSAGE, args));
        }
        // Вложения — отдельными сообщениями (не caption'ом): сбой доставки одного не топит
        // остальные, а send_* сам резолвит agf_ в байты (см. TelegramToolService.sendMedia).
        for (Part part : outbound.parts()) {
            MediaTool tool = mediaTool(part.type());
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("chatId", chatId.toString());
            args.put(tool.param(), part.storageRef());
            // Контентный ключ идемпотентности (messageId:fileId): устойчив к изменению состава
            // parts между ретраями — позиционный суффикс давал бы InputConflict при сдвиге.
            // Дубль одного файла в сообщении схлопывается в replay и доставляется один раз.
            requests.add(request(config, dispatch.messageId() + ":" + part.storageRef(), tool.name(), args));
        }
        return requests;
    }

    private record MediaTool(String name, String param) {}

    private static MediaTool mediaTool(String partType) {
        return switch (partType) {
            case "image" -> new MediaTool(TOOL_SEND_PHOTO, "photo");
            case "video" -> new MediaTool(TOOL_SEND_VIDEO, "video");
            default -> new MediaTool(TOOL_SEND_DOCUMENT, "document");
        };
    }

    private static ToolCallRequest request(ChannelConfig config, String id, String tool,
                                           Map<String, Object> args) {
        return ToolCallRequest.builder()
                .id(id)
                .connectorCode(config.connectorCode())
                .connectionId(config.connectionId())
                .name(tool)
                .input(args)
                .build();
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
