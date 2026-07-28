package ru.agimate.controlapi.database.enums;

/**
 * Where a connector's capability definitions (tools and triggers) come from.
 * <ul>
 *   <li>{@link #STATIC} — a fixed type-level set: tools by reflection over {@code @Tool} methods,
 *       triggers from {@code TriggerProvider} (telegram, time, board, persist-memory).</li>
 *   <li>{@link #DYNAMIC} — a per-instance set, discovered at runtime and cached in
 *       {@code connection_tools}/{@code connection_triggers} (MCP servers, device apps).</li>
 * </ul>
 */
public enum DefinitionBinding {
    STATIC,
    DYNAMIC
}
