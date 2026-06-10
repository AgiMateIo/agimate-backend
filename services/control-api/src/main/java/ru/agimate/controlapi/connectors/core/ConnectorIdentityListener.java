package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorModifiedEvent;
import ru.agimate.controlapi.connectors.core.tasks.ConnectorTaskService;

/**
 * Превращает lifecycle-события экземпляров коннекторов в строки {@code connector_tasks}
 * (декларация — {@code handler.getTasks()}).
 *
 * <p>Pull‑модель не требует сообщать scheduler'у об изменениях — он сам прочитает новую/удалённую
 * строку на ближайшем тике (≤1с). Поэтому listener только пишет в БД и не публикует ничего обратно.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT} гарантирует, что строка не появится, если внешняя
 * транзакция (создание интеграции) откатилась. {@code fallbackExecution=true} даёт обработку
 * событий, опубликованных вне транзакции (тесты).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorIdentityListener {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorTaskService taskService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(ConnectorCreatedEvent event) {
        ConnectorHandler handler = connectorRegistry.findHandler(event.connectorCode()).orElse(null);
        if (handler == null) {
            log.warn("ConnectorCreatedEvent({}, {}): no handler in registry — skipping",
                    event.connectorCode(), event.identity());
            return;
        }
        for (TaskSpecification spec : handler.getTasks().values()) {
            taskService.upsert(event.connectorCode(), event.identity(), spec);
            log.info("Registered task {}/{}/{}", event.connectorCode(), event.identity(), spec.name());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onModified(ConnectorModifiedEvent event) {
        ConnectorHandler handler = connectorRegistry.findHandler(event.connectorCode()).orElse(null);
        if (handler == null) {
            log.warn("ConnectorModifiedEvent({}, {}): no handler in registry — skipping",
                    event.connectorCode(), event.identity());
            return;
        }
        taskService.syncIdentity(event.connectorCode(), event.identity(), handler.getTasks().values());
        log.info("Synced tasks for {}/{}", event.connectorCode(), event.identity());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(ConnectorDeletedEvent event) {
        int removed = taskService.deleteByIdentity(event.connectorCode(), event.identity());
        if (removed > 0) {
            log.info("Removed {} task row(s) for {}/{}",
                    removed, event.connectorCode(), event.identity());
        }
    }
}
