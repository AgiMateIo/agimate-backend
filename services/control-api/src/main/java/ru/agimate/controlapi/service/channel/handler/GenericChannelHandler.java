package ru.agimate.controlapi.service.channel.handler;

import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.controller.agent.dto.ToolCallRequest;
import ru.agimate.controlapi.service.channel.InputFilterEvaluator;
import ru.agimate.controlapi.service.channel.PlaceholderRenderer;
import ru.agimate.controlapi.service.channel.handler.dto.*;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The universal data-driven handler: it reproduces the previous behaviour of channels from
 * {@code config}.
 *
 * <p>It covers dynamic connectors (e.g. {@code app}, where triggers and tools are defined by the app itself),
 * for which a code handler cannot be written. The mapping is given by settings:
 * <ul>
 *   <li>{@code triggers} — the list of trigger names;</li>
 *   <li>{@code messageField} — the dot-path in {@code trigger.data} to the message's text;</li>
 *   <li>{@code replyConnectionId}/{@code replyToolName} — the reply target (the connector is derived from connectionId);</li>
 *   <li>{@code replyToolParams} — a template with the placeholders {@code {text}} and {@code {trigger.*}}.</li>
 * </ul>
 */
@Component
public class GenericChannelHandler implements ChannelHandler {

    public static final String NAME = "generic";

    private static final String K_TRIGGERS = "triggers";
    private static final String K_MESSAGE_FIELD = "messageField";
    private static final String K_REPLY_CONNECTION_ID = "replyConnectionId";
    private static final String K_REPLY_TOOL = "replyToolName";
    private static final String K_REPLY_PARAMS = "replyToolParams";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Map<String, Object> getConfigFields() {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put(K_TRIGGERS, ConfigSchema.arrayProp("string", "Триггеры",
                "Имена триггеров коннектора, которые слушает канал"));
        props.put(K_MESSAGE_FIELD, ConfigSchema.prop("string", "Поле сообщения",
                "Dot-path внутри trigger.data до текста сообщения (например data.message.text)"));
        props.put(K_REPLY_CONNECTION_ID, ConfigSchema.prop("string", "Reply connection",
                "connection_id reply-цели (коннектор выводится из него)"));
        props.put(K_REPLY_TOOL, ConfigSchema.prop("string", "Reply tool",
                "Тул, отправляющий ответ"));
        props.put(K_REPLY_PARAMS, ConfigSchema.prop("object", "Шаблон параметров",
                "Шаблон параметров тула с плейсхолдерами {text} и {trigger.*}"));
        return ConfigSchema.schema(props,
                K_TRIGGERS, K_MESSAGE_FIELD, K_REPLY_CONNECTION_ID, K_REPLY_TOOL, K_REPLY_PARAMS);
    }

    @Override
    public List<TriggerDefinition> listOfTriggers(ChannelConfig config) {
        return triggerNames(config).stream().map(TriggerDefinition::new).toList();
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of(new ToolDefinition(replyConnectionId(config), replyTool(config)));
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (triggerNames(config).isEmpty()) {
            throw new ConnectorException("config.triggers must be a non-empty list of trigger names");
        }
        require(messageField(config), "config.messageField");
        require(replyConnectionId(config), "config.replyConnectionId");
        require(replyTool(config), "config.replyToolName");
        if (replyParams(config) == null) {
            throw new ConnectorException("config.replyToolParams is required");
        }
    }

    @Override
    public Optional<InboundMessage> handleInput(ChannelConfig config, Trigger trigger) {
        Object value = InputFilterEvaluator.resolvePath(trigger.data(), messageField(config));
        String text = value != null ? value.toString() : JsonUtils.writeValueAsString(trigger.data());
        return Optional.of(InboundMessage.text(text));
    }

    @Override
    public List<ToolCallRequest> handleOutput(ChannelConfig config, OutboundMessage outbound,
                                                  OutboundDispatch dispatch) {
        Map<String, Object> args = PlaceholderRenderer.render(
                replyParams(config), outbound.text(), dispatch.replyContext());
        return List.of(ToolCallRequest.builder()
                .id(dispatch.messageId())
                .connectionId(replyConnectionId(config))
                .name(replyTool(config))
                .input(args)
                .build());
    }

    // --- config accessors ---

    @SuppressWarnings("unchecked")
    private List<String> triggerNames(ChannelConfig config) {
        Object raw = config.setting(K_TRIGGERS);
        if (raw instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }

    private String messageField(ChannelConfig config) {
        return asString(config.setting(K_MESSAGE_FIELD));
    }

    private String replyConnectionId(ChannelConfig config) {
        return asString(config.setting(K_REPLY_CONNECTION_ID));
    }

    private String replyTool(ChannelConfig config) {
        return asString(config.setting(K_REPLY_TOOL));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> replyParams(ChannelConfig config) {
        Object raw = config.setting(K_REPLY_PARAMS);
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ConnectorException(field + " is required");
        }
    }
}
