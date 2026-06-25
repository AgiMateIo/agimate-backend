package ru.agimate.controlapi.database.enums;

/**
 * Под каким ключом ({@code connections.scope_id}) живёт экземпляр коннектора — кто его «носитель».
 * Type-level коннектор объявляет набор поддерживаемых scope ({@code connectors.supported_scopes}) и
 * дефолт; конкретное подключение фиксирует выбранный scope в {@code connections.identity_scope}.
 *
 * <ul>
 *   <li>{@link #INSTANCE} — явный экземпляр, созданный пользователем (telegram-бот, MCP-сервер).
 *       {@code scope_id = null}; носитель — сам {@code connections.id}.</li>
 *   <li>{@link #AGENT} — на агента ({@code scope_id = agentId}); память/время по умолчанию.</li>
 *   <li>{@link #TEAM} — общий для команды ({@code scope_id = teamId}); board, командная память.</li>
 *   <li>{@link #USER} — общий для всех агентов пользователя ({@code scope_id = userId}).</li>
 *   <li>{@link #GLOBAL} — единый глобальный ({@code scope_id = null}).</li>
 * </ul>
 */
public enum IdentityScope {
    INSTANCE,
    AGENT,
    TEAM,
    USER,
    GLOBAL
}
