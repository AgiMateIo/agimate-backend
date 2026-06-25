package ru.agimate.controlapi.database.enums;

/**
 * Между кем шарится identity/состояние экземпляра коннектора.
 * <ul>
 *   <li>{@link #PRIVATE} — приватно владельцу ({@code user_id}).</li>
 *   <li>{@link #TEAM_SHARED} — общее для команды (например board).</li>
 *   <li>{@link #GLOBAL} — глобально доступное.</li>
 * </ul>
 */
public enum SharingScope {
    PRIVATE,
    TEAM_SHARED,
    GLOBAL
}
