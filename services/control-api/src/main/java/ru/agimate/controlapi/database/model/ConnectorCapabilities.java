package ru.agimate.controlapi.database.model;

import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.SharingScope;
import ru.agimate.controlapi.database.enums.ToolBinding;
import ru.agimate.controlapi.database.enums.TransportDirection;

/**
 * Type-level capability-дескриптор коннектора (4 оси, на которых реально ветвится код).
 * Объявляется в {@code ConnectorHandler.capabilities()}, персистится в каталог {@code connectors}
 * ({@code capabilities} JSONB) бутстрапом — источник истины код. Маршрутизация исполнения читает
 * {@link #executionLocus()} с сущности {@code Connector} (включая connector {@code app}, у которого
 * нет handler-бина).
 *
 * @param transportDirection кто инициирует соединение (семантика секрета)
 * @param executionLocus     где исполняется тул (роутинг вызова)
 * @param toolBinding        статический набор тулов (рефлексия) или динамический ({@code connection_tools})
 * @param sharingScope       скоуп шаринга identity/состояния
 */
public record ConnectorCapabilities(
        TransportDirection transportDirection,
        ExecutionLocus executionLocus,
        ToolBinding toolBinding,
        SharingScope sharingScope
) {

    /** Internal-сервис: backend-исполнение, статические тулы, приватный. */
    public static ConnectorCapabilities internal() {
        return new ConnectorCapabilities(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND,
                ToolBinding.STATIC, SharingScope.PRIVATE);
    }

    /** Outbound-интеграция со статическими тулами (telegram). */
    public static ConnectorCapabilities staticIntegration() {
        return new ConnectorCapabilities(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND,
                ToolBinding.STATIC, SharingScope.PRIVATE);
    }

    /** Outbound-интеграция с динамическими per-instance тулами (MCP). */
    public static ConnectorCapabilities dynamicIntegration() {
        return new ConnectorCapabilities(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND,
                ToolBinding.DYNAMIC, SharingScope.PRIVATE);
    }

    /** Inbound-устройство: исполнение на устройстве, динамические тулы (app). */
    public static ConnectorCapabilities device() {
        return new ConnectorCapabilities(
                TransportDirection.INBOUND, ExecutionLocus.EXTERNAL,
                ToolBinding.DYNAMIC, SharingScope.PRIVATE);
    }

    /** Loopback/agent-side: исполняет агент, control-api лишь авторизует. */
    public static ConnectorCapabilities loopback() {
        return new ConnectorCapabilities(
                TransportDirection.INBOUND, ExecutionLocus.AGENT,
                ToolBinding.STATIC, SharingScope.PRIVATE);
    }
}
