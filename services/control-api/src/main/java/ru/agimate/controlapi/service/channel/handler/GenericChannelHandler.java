package ru.agimate.controlapi.service.channel.handler;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.service.channel.ChannelOutboundDispatcher;
import ru.agimate.controlapi.service.channel.InputFilterEvaluator;
import ru.agimate.controlapi.service.channel.PlaceholderRenderer;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Универсальный data-driven handler: воспроизводит прежнее поведение каналов из {@code config}.
 *
 * <p>Покрывает динамические коннекторы (например {@code app}, где триггеры/тулы задаются
 * пер-устройство), где код-handler написать нельзя. Маппинг задаётся настройками:
 * <ul>
 *   <li>{@code triggers} — список имён триггеров;</li>
 *   <li>{@code messageField} — dot-path в {@code trigger.data} до текста сообщения;</li>
 *   <li>{@code replyConnectorCode}/{@code replyIdentity}/{@code replyToolName} — reply-цель;</li>
 *   <li>{@code replyToolParams} — шаблон с плейсхолдерами {@code {text}} и {@code {trigger.*}}.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class GenericChannelHandler implements ChannelHandler {

    public static final String NAME = "generic";

    private static final String K_TRIGGERS = "triggers";
    private static final String K_MESSAGE_FIELD = "messageField";
    private static final String K_REPLY_CONNECTOR = "replyConnectorCode";
    private static final String K_REPLY_IDENTITY = "replyIdentity";
    private static final String K_REPLY_TOOL = "replyToolName";
    private static final String K_REPLY_PARAMS = "replyToolParams";

    private final ChannelOutboundDispatcher dispatcher;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<TriggerDefinition> listOfTriggers(ChannelConfig config) {
        return triggerNames(config).stream().map(TriggerDefinition::new).toList();
    }

    @Override
    public List<ToolDefinition> listOfTools(ChannelConfig config) {
        return List.of(new ToolDefinition(replyConnector(config), replyIdentity(config), replyTool(config)));
    }

    @Override
    public void validateConfig(ChannelConfig config) {
        if (triggerNames(config).isEmpty()) {
            throw new ConnectorException("config.triggers must be a non-empty list of trigger names");
        }
        require(messageField(config), "config.messageField");
        require(replyConnector(config), "config.replyConnectorCode");
        require(replyIdentity(config), "config.replyIdentity");
        require(replyTool(config), "config.replyToolName");
        if (replyParams(config) == null) {
            throw new ConnectorException("config.replyToolParams is required");
        }
    }

    @Override
    public Optional<InboundMessage> convert(ChannelConfig config, Trigger trigger) {
        Object value = InputFilterEvaluator.resolvePath(trigger.data(), messageField(config));
        String text = value == null ? null : value.toString();
        return Optional.of(InboundMessage.text(text, trigger.data(), null));
    }

    @Override
    public void process(ChannelConfig config, OutboundMessage outbound, ChannelOutboundContext ctx) {
        Map<String, Object> args = PlaceholderRenderer.render(
                replyParams(config), outbound.text(), outbound.replyContext());
        dispatcher.dispatch(ctx.agentId(), ctx.userId(),
                replyConnector(config), replyIdentity(config), replyTool(config), args, ctx.toolCallId());
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

    private String replyConnector(ChannelConfig config) {
        return asString(config.setting(K_REPLY_CONNECTOR));
    }

    private String replyIdentity(ChannelConfig config) {
        return asString(config.setting(K_REPLY_IDENTITY));
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
