package ru.agimate.controlapi.database.enums;

/**
 * Где физически исполняется тул коннектора — определяет роутинг вызова.
 * <ul>
 *   <li>{@link #BACKEND} — исполняет control-api in-proc (internal-сервисы, telegram, mcp-proxy).
 *       Вызов идёт в {@code ToolExecutionService}.</li>
 *   <li>{@link #EXTERNAL} — исполняет внешнее устройство; control-api лишь пушит вызов
 *       (app → Centrifugo {@code app:{appId}}). Сам не исполняет.</li>
 *   <li>{@link #AGENT} — исполняет агент на своей стороне; control-api только проверяет право
 *       (ABAC) и не исполняет.</li>
 * </ul>
 */
public enum ExecutionLocus {
    BACKEND,
    EXTERNAL,
    AGENT
}
