package ru.agimate.controlapi.database.enums;

/**
 * Что уточняет правило {@code agent_connection_policies}: {@link #TOOL} — аргументы вызова тула,
 * {@link #TRIGGER} — параметры входящего триггера. Единая таблица политик разделяется по этому
 * дискриминатору (заменяет раздельные {@code agent_tool_policies}/{@code agent_trigger_policies}).
 */
public enum PolicyKind {
    TOOL,
    TRIGGER
}
