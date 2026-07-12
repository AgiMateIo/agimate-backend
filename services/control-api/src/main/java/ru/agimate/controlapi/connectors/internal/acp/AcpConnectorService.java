package ru.agimate.controlapi.connectors.internal.acp;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.TriggerProvider;
import ru.agimate.controlapi.connectors.core.dto.TriggerSpec;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;
import ru.agimate.controlapi.database.model.ConnectorCapabilities;

import java.util.List;
import java.util.Map;

/**
 * Фасад ACP-коннектора (Agent Client Protocol): диалог с агентом из IDE (Zed и другие
 * ACP-клиенты) через WebSocket-эндпоинт {@code /acp}.
 *
 * <p>Устройство повторяет webchat: одна connection на пользователя ({@code scope = USER},
 * материализуется binding'ом при первом {@code session/new}), каналы — per-agent. Входящие
 * шлёт {@code AcpService} (триггер {@code message_received} с явными sessionId/audience),
 * доставка ответов — {@code AcpChannelHandler} (JSON-RPC {@code session/update} в живое
 * соединение). Тулов и джоб нет — реализуется один {@link TriggerProvider}.
 */
@Component
public class AcpConnectorService implements InternalConnectorHandler, TriggerProvider {

    public static final String CONNECTOR_CODE = "acp";
    public static final String TRIGGER_MESSAGE_RECEIVED = "message_received";

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "ACP (IDE)";
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
                "Message from the user typed in the IDE (ACP client)",
                List.of("sessionId", "messageId", "text")));
    }
}
