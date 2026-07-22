package ru.agimate.controlapi.connectors.internal.webchat;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;

import java.util.List;
import java.util.Map;

/**
 * Фасад webchat-коннектора: чат пользователя с агентом из собственного фронта.
 *
 * <p>Одна connection на пользователя (строка-режим, материализуется
 * {@code ConnectionBindingService} при первом чате); владельца данных коннектор не выводит —
 * каждое взаимодействие приходит с явным адресом (sessionId → канал → агент). Агенты подключаются к ней
 * {@code agent_connections}-binding'ами, каналы — per-agent. Входящие сообщения фронт шлёт через
 * {@code /manage/webchat/...} (триггер {@code message_received} с явными sessionId/audience),
 * доставка ответов — {@code WebchatChannelHandler} (webchat_messages + Centrifugo). Тулов и джоб
 * нет — из capability-интерфейсов реализуется один {@link TriggerProvider}.
 */
@Component
public class WebchatConnectorService implements InternalConnectorHandler, TriggerProvider {

    @Override
    public String connectorCode() {
        return WebchatChannelHandler.CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Webchat";
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(WebchatChannelHandler.TRIGGER_MESSAGE_RECEIVED, new TriggerSpec(
                "Message from the user typed in the web chat",
                List.of("sessionId", "messageId", "text")));
    }
}
