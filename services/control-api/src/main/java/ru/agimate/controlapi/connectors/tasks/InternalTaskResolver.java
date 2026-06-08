package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.internal.ServerSideToolHandler;
import ru.agimate.controlapi.connectors.internal.ServerSideToolRegistry;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind;

import java.util.Optional;

/**
 * Резолвит {@link Task} для строк {@code connector_tasks} со scope
 * {@link ConnectorTaskScopeKind#GLOBAL} и {@link ConnectorTaskScopeKind#USER} — то есть для
 * задач internal‑коннекторов ({@link ServerSideToolHandler}). INTEGRATION scope этому resolver'у
 * не достаётся, его обрабатывает {@code IntegrationTaskResolver}.
 *
 * <p>Internal handler — singleton, поэтому в отличие от Integration здесь нет загрузки
 * credentials из БД на каждом resolve. Достаточно дёрнуть {@code getBackgroundTasks()} у handler'а
 * и найти нужный дескриптор по {@code task_code}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTaskResolver implements TaskResolver {

    private final ServerSideToolRegistry registry;

    @Override
    public Optional<Task> resolve(ConnectorTask row) {
        if (row.getScopeKind() == ConnectorTaskScopeKind.INTEGRATION) {
            return Optional.empty();
        }
        ServerSideToolHandler handler;
        try {
            handler = registry.getHandler(row.getConnectorCode());
        } catch (Exception e) {
            log.warn("No internal handler for connector {} — cannot resolve task {}",
                    row.getConnectorCode(), row.getTaskCode());
            return Optional.empty();
        }
        return handler.getBackgroundTasks().stream()
                .filter(d -> row.getTaskCode().equals(d.taskCode()))
                .map(TaskDescriptor::task)
                .findFirst();
    }
}
