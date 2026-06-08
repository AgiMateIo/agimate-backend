package ru.agimate.controlapi.database.enums;

/**
 * Категория «к чему привязана» строка в {@code connector_tasks}.
 * Соответствует sealed‑hierarchy {@code TaskScope} на уровне доменной модели.
 */
public enum ConnectorTaskScopeKind {
    GLOBAL,
    INTEGRATION,
    USER
}
