package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.internal.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.internal.InternalConnectorRegistry;
import ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind;

import java.util.List;

/**
 * Регистрирует Global фоновые задачи internal‑коннекторов в {@code connector_tasks} при старте
 * приложения.
 *
 * <p>В отличие от интеграций (lifecycle через {@code IntegrationCreatedEvent}), у internal‑handler'ов
 * нет наблюдаемого «создания» — они существуют с момента старта Spring. Поэтому единственный
 * способ донести их Global задачи до scheduler'а — пройтись по registry один раз после
 * {@link ApplicationReadyEvent} и сделать идемпотентный upsert по бизнес‑ключу. Это не reconciliation
 * (никакого periodic sync'а нет), а one‑shot инициализация.
 *
 * <p>USER‑scope задачи bootstrap пропускает: для них требуется явный lifecycle hook
 * (новый пользователь / новая сущность), которого пока нет — handler сам сделает upsert в нужный момент.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InternalTaskBootstrap {

    private final InternalConnectorRegistry registry;
    private final ConnectorTaskService taskService;

    @EventListener(ApplicationReadyEvent.class)
    public void registerGlobalTasks() {
        for (InternalConnectorHandler handler : registry.getAvailableHandlers()) {
            String connectorCode = handler.getConnectorCode();
            List<TaskDescriptor> descriptors = handler.getBackgroundTasks();
            for (TaskDescriptor descriptor : descriptors) {
                ConnectorTaskScopeKind kind = descriptor.scope().kind();
                if (kind != ConnectorTaskScopeKind.GLOBAL) {
                    log.warn("Skipping {}/{}: bootstrap only registers GLOBAL tasks, but scope is {}; "
                                    + "handler should upsert it explicitly when the owning entity appears",
                            connectorCode, descriptor.taskCode(), kind);
                    continue;
                }
                taskService.upsertFromDescriptor(connectorCode, TaskScope.global(), null, descriptor);
                log.info("Registered global background task {}/{}", connectorCode, descriptor.taskCode());
            }
        }
    }
}
