package ru.agimate.controlapi.database.enums;

/**
 * Откуда берутся определения капабилити коннектора (тулы и триггеры).
 * <ul>
 *   <li>{@link #STATIC} — фиксированный набор уровня типа: тулы рефлексией из {@code @Tool}-методов,
 *       триггеры из {@code TriggerProvider} (telegram, time, board, persist-memory).</li>
 *   <li>{@link #DYNAMIC} — набор per-instance, открывается в рантайме и кэшируется в
 *       {@code connection_tools}/{@code connection_triggers} (MCP-серверы, device-apps).</li>
 * </ul>
 */
public enum DefinitionBinding {
    STATIC,
    DYNAMIC
}
