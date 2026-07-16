package ru.agimate.controlapi.database.model;

import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.ExecutionLocus;
import ru.agimate.controlapi.database.enums.IdentityScope;
import ru.agimate.controlapi.database.enums.TransportDirection;

import java.util.List;

/**
 * Type-level дескриптор коннектора: декларативные характеристики того, как он подключён и исполняется
 * (в отличие от à la carte capability-интерфейсов {@code ToolProvider}/… — «что коннектор предоставляет»).
 * Объявляется в {@code ConnectorHandler.traits()}, персистится в каталог {@code connectors} бутстрапом —
 * источник истины код. Маршрутизация исполнения читает {@link #executionLocus()} с сущности
 * {@code Connector} (включая connector {@code app}, у которого нет handler-бина).
 *
 * @param transportDirection кто инициирует соединение (семантика секрета; для DELEGATED-locus
 *                           определяет механику диспатча: OUTBOUND — прокси in-proc, INBOUND — push)
 * @param executionLocus     кто выполняет работу тула (граница доверия; вместе с
 *                           transportDirection задаёт роутинг вызова)
 * @param definitionBinding  откуда определения тулов/триггеров: STATIC (рефлексия/SPI) или DYNAMIC
 *                           ({@code connection_tools}/{@code connection_triggers})
 * @param supportedScopes    какие {@link IdentityScope} коннектор поддерживает (подключение выбирает
 *                           один; первый элемент — дефолт)
 */
public record ConnectorTraits(
        TransportDirection transportDirection,
        ExecutionLocus executionLocus,
        DefinitionBinding definitionBinding,
        List<IdentityScope> supportedScopes
) {

    /** Internal-сервис: backend-исполнение, статические тулы, на агента. */
    public static ConnectorTraits internal() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.BACKEND, DefinitionBinding.STATIC,
                List.of(IdentityScope.AGENT));
    }

    /** Outbound-интеграция со статическими тулами (telegram): работу выполняет внешняя платформа. */
    public static ConnectorTraits staticIntegration() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.DELEGATED, DefinitionBinding.STATIC,
                List.of(IdentityScope.INSTANCE));
    }

    /** Outbound-интеграция с динамическими per-instance тулами (MCP): исполняет внешний сервер. */
    public static ConnectorTraits dynamicIntegration() {
        return new ConnectorTraits(
                TransportDirection.OUTBOUND, ExecutionLocus.DELEGATED, DefinitionBinding.DYNAMIC,
                List.of(IdentityScope.INSTANCE));
    }

    /** Inbound-устройство (app): исполняет устройство, вызов доставляется push'ем. */
    public static ConnectorTraits device() {
        return new ConnectorTraits(
                TransportDirection.INBOUND, ExecutionLocus.DELEGATED, DefinitionBinding.DYNAMIC,
                List.of(IdentityScope.INSTANCE));
    }

    /** Loopback/agent-side: исполняет агент, control-api лишь авторизует. */
    public static ConnectorTraits loopback() {
        return new ConnectorTraits(
                TransportDirection.INBOUND, ExecutionLocus.AGENT, DefinitionBinding.STATIC,
                List.of(IdentityScope.AGENT));
    }
}
