package ru.agimate.controlapi.database.enums;

/**
 * Откуда берётся набор тулов коннектора.
 * <ul>
 *   <li>{@link #STATIC} — фиксированный набор, строится рефлексией из {@code @Tool}-методов один
 *       раз (telegram, time, board, persist-memory).</li>
 *   <li>{@link #DYNAMIC} — набор per-instance, открывается в рантайме и кэшируется в
 *       {@code connection_tools} (MCP-серверы, device-apps).</li>
 * </ul>
 */
public enum ToolBinding {
    STATIC,
    DYNAMIC
}
