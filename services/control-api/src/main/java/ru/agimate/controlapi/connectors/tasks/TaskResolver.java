package ru.agimate.controlapi.connectors.tasks;

import ru.agimate.controlapi.database.entities.ConnectorTask;

import java.util.Optional;

/**
 * Мост между строкой {@code connector_tasks} и исполняемым {@link Task}. Реализации появятся в
 * шагах 3/4: {@code IntegrationTaskResolver} (для
 * {@link ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind#INTEGRATION}) и
 * {@code InternalTaskResolver} (для GLOBAL/USER).
 *
 * <p>Это не «синхронизация» а простой lookup — параметры расписания берутся из строки
 * ({@code task_type}, {@code config}), а resolver добывает только исполняемое тело по
 * {@code (connector_code, task_code, scope)}.
 *
 * <p>Scheduler опрашивает зарегистрированные resolver'ы по очереди; первый отозвавшийся wins.
 * Если никто не отозвался — задача завершается с ошибкой («No resolver») и переходит в PENDING.
 */
public interface TaskResolver {

    Optional<Task> resolve(ConnectorTask row);
}
