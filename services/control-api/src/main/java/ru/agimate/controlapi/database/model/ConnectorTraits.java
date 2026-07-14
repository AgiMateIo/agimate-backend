package ru.agimate.controlapi.database.model;

import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;

import java.util.List;

/**
 * Type-level дескриптор коннектора: декларативные характеристики того, как он подключён и исполняется
 * (в отличие от à la carte capability-интерфейсов {@code ToolProvider}/… — «что коннектор предоставляет»).
 * Объявляется в {@code ConnectorHandler.traits()}, персистится в каталог {@code connectors} бутстрапом —
 * источник истины код. Маршрутизация исполнения читает {@link #executionLocus()} с сущности
 * {@code Connector} (включая connector {@code app}, у которого нет handler-бина).
 *
 * @param transportDirection кто инициирует соединение (семантика секрета)
 * @param executionLocus     где исполняется тул (роутинг вызова)
 * @param toolBinding        статический набор тулов (рефлексия) или динамический ({@code connection_tools})
 * @param supportedScopes    какие {@link IdentityScope} коннектор поддерживает (подключение выбирает один)
 * @param defaultScope       scope по умолчанию (∈ {@code supportedScopes})
 */
public record ConnectorTraits(
        TransportDirection transportDirection,
        ExecutionLocus executionLocus,
        ToolBinding toolBinding,
        List<IdentityScope> supportedScopes,
        IdentityScope defaultScope
) {

    /** Internal-сервис: backend-исполнение, статические тулы, на агента. */
    public static ConnectorTraits internal() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, ToolBinding.STATIC,
                List.of(IdentityScope.AGENT), IdentityScope.AGENT);
    }

    /** Outbound-интеграция со статическими тулами (telegram) — явный экземпляр. */
    public static ConnectorTraits staticIntegration() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, ToolBinding.STATIC,
                List.of(IdentityScope.INSTANCE), IdentityScope.INSTANCE);
    }

    /** Outbound-интеграция с динамическими per-instance тулами (MCP) — явный экземпляр. */
    public static ConnectorTraits dynamicIntegration() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, ToolBinding.DYNAMIC,
                List.of(IdentityScope.INSTANCE), IdentityScope.INSTANCE);
    }

    /** Inbound-устройство: исполнение на устройстве, динамические тулы (app) — явный экземпляр. */
    public static ConnectorTraits device() {
        return new ConnectorTraits(
                TransportDirection.INBOUND, ExecutionLocus.EXTERNAL, ToolBinding.DYNAMIC,
                List.of(IdentityScope.INSTANCE), IdentityScope.INSTANCE);
    }

    /** Loopback/agent-side: исполняет агент, control-api лишь авторизует. */
    public static ConnectorTraits loopback() {
        return new ConnectorTraits(
                TransportDirection.INBOUND, ExecutionLocus.AGENT, ToolBinding.STATIC,
                List.of(IdentityScope.AGENT), IdentityScope.AGENT);
    }
}
