package ru.agimate.controlapi.connectors.internal.webchat;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorContext;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

import java.util.List;
import java.util.Map;

/**
 * Фасад webchat-коннектора: чат пользователя с агентом из собственного фронта.
 *
 * <p>Одна connection на пользователя ({@code scope = USER}, материализуется
 * {@code ConnectionBindingService} при первом чате); агенты подключаются к ней
 * {@code agent_connections}-binding'ами, каналы — per-agent. Входящие сообщения фронт шлёт через
 * {@code /manage/webchat/...} (триггер {@code message_received} с явными sessionId/audience),
 * доставка ответов — {@code WebchatChannelHandler} (webchat_messages + Centrifugo). Тулов и джоб
 * нет: без BaseConnectorHandler, реализация интерфейса напрямую.
 */
@Component
public class WebchatConnectorService implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "webchat";
    public static final String TRIGGER_MESSAGE_RECEIVED = "message_received";

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Webchat";
    }

    /** Одна connection на пользователя: все его агенты делят её через binding'и. */
    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, ToolBinding.STATIC,
                List.of(IdentityScope.USER), IdentityScope.USER);
    }

    @Override
    public Map<String, TriggerSpec> getTriggers() {
        return Map.of(TRIGGER_MESSAGE_RECEIVED, new TriggerSpec(
                "Message from the user typed in the web chat",
                List.of("sessionId", "messageId", "text")));
    }

    @Override
    public Map<String, ConnectorToolSpec> getTools() {
        return Map.of();
    }

    @Override
    public Map<String, Object> executeTool(ConnectorContext context, String toolName, Map<String, Object> args) {
        throw new ConnectorException("webchat connector has no tools");
    }

    @Override
    public Map<String, Object> executeJob(ConnectorContext context, String name, Map<String, Object> args) {
        throw new ConnectorException("webchat connector has no jobs");
    }
}
