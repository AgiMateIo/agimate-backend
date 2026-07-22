package ru.agimate.controlapi.database.model;

import ru.agimate.controlapi.database.enums.DefinitionBinding;
import ru.agimate.controlapi.database.enums.ExecutionKind;

/**
 * Type-level дескриптор коннектора: только функциональные оси — те, на которых ветвится механика.
 * Объявляется в {@code ConnectorHandler.traits()}, персистится в каталог {@code connectors}
 * бутстрапом — источник истины код. Остальные различия коннекторов не декларируются:
 * экземплярность выводится ({@code Connector.isInstanceBearing()}: пользователь приносит
 * идентичность экземпляра — credentials или device-регистрацию), правило владельца данных
 * (agent/team/user) воплощено в коде каждого коннектора и описано в
 * {@code docs/connectors/architecture.md}.
 *
 * @param executionKind     кто исполняет вызов тула — читает {@code ConnectorService.pushToConnector}
 * @param definitionBinding откуда определения тулов/триггеров: STATIC (рефлексия/SPI) или DYNAMIC
 *                          ({@code connection_tools}/{@code connection_triggers}) — читает листинг
 */
public record ConnectorTraits(
        ExecutionKind executionKind,
        DefinitionBinding definitionBinding
) {

    /** Дефолт: backend-исполнение, статические тулы (internal-сервисы и интеграции вроде telegram). */
    public static ConnectorTraits internal() {
        return new ConnectorTraits(ExecutionKind.BACKEND, DefinitionBinding.STATIC);
    }

    /** Интеграция с динамическими per-instance тулами (MCP): определения из {@code connection_tools}. */
    public static ConnectorTraits dynamicIntegration() {
        return new ConnectorTraits(ExecutionKind.BACKEND, DefinitionBinding.DYNAMIC);
    }

    /** Устройство (app): исполняет девайс, вызов доставляется push'ем, тулы динамические. */
    public static ConnectorTraits device() {
        return new ConnectorTraits(ExecutionKind.DEVICE, DefinitionBinding.DYNAMIC);
    }

    /** Loopback/agent-side (claude-code): исполняет агент, control-api лишь авторизует. */
    public static ConnectorTraits loopback() {
        return new ConnectorTraits(ExecutionKind.LOOPBACK, DefinitionBinding.STATIC);
    }
}
