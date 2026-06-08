package ru.agimate.controlapi.connectors.tasks;

import ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind;

import java.util.UUID;

/**
 * К чему привязан экземпляр фоновой задачи.
 * <ul>
 *   <li>{@link #global()} — одна задача на коннектор, без привязки к сущности ({@code id == null}).</li>
 *   <li>{@link #integration(UUID)} — задача конкретной интеграции (по {@code integration_credentials.id}).</li>
 *   <li>{@link #user(UUID)} — per‑user задача internal‑коннектора (по {@code users.id}).</li>
 * </ul>
 */
public record TaskScope(ConnectorTaskScopeKind kind, UUID id) {

    public TaskScope {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        boolean global = kind == ConnectorTaskScopeKind.GLOBAL;
        if (global && id != null) {
            throw new IllegalArgumentException("GLOBAL scope must have null id");
        }
        if (!global && id == null) {
            throw new IllegalArgumentException(kind + " scope must have non-null id");
        }
    }

    public static TaskScope global() {
        return new TaskScope(ConnectorTaskScopeKind.GLOBAL, null);
    }

    public static TaskScope integration(UUID integrationId) {
        return new TaskScope(ConnectorTaskScopeKind.INTEGRATION, integrationId);
    }

    public static TaskScope user(UUID userId) {
        return new TaskScope(ConnectorTaskScopeKind.USER, userId);
    }
}
