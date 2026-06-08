package ru.agimate.controlapi.connectors.tasks;

import java.util.UUID;

/**
 * Однозначный идентификатор экземпляра запущенной задачи.
 * Используется как ключ в in‑memory мапе шедулера и компонент имени потока / MDC / метрик.
 *
 * <p>Соответствует уникальному бизнес‑ключу таблицы {@code connector_tasks}:
 * {@code (connector_code, scope_kind, scope_id, task_code)}.
 */
public record TaskKey(String connectorCode, TaskScope scope, String taskCode) {

    /** Строковое представление для логов: {@code telegram/long-poll/integration:<uuid>}. */
    public String asString() {
        UUID id = scope.id();
        String scopePart = id == null
                ? scope.kind().name().toLowerCase()
                : scope.kind().name().toLowerCase() + ":" + id;
        return connectorCode + "/" + taskCode + "/" + scopePart;
    }

    @Override
    public String toString() {
        return asString();
    }
}
