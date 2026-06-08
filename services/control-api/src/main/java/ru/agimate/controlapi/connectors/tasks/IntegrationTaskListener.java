package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.connectors.integrations.IntegrationHandler;
import ru.agimate.controlapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.controlapi.connectors.integrations.events.IntegrationCreatedEvent;
import ru.agimate.controlapi.connectors.integrations.events.IntegrationDeletedEvent;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;

import java.util.List;

/**
 * Превращает события lifecycle интеграций в строки {@code connector_tasks}.
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
public class IntegrationTaskListener {

    private final ConnectorTaskService taskService;
    private final IntegrationsRegistry integrationsRegistry;
    private final IntegrationCredentialsRepository credentialsRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCreated(IntegrationCreatedEvent event) {
        IntegrationCredentials creds = credentialsRepository.findByIdNotDeleted(event.integrationId())
                .orElse(null);
        if (creds == null) {
            log.debug("IntegrationCreatedEvent({}, {}): credentials not found — skipping",
                    event.integrationId(), event.connectorCode());
            return;
        }
        IntegrationHandler handler = integrationsRegistry.findHandler(event.connectorCode()).orElse(null);
        if (handler == null) {
            log.warn("IntegrationCreatedEvent({}, {}): no handler in registry — skipping",
                    event.integrationId(), event.connectorCode());
            return;
        }

        List<TaskDescriptor> descriptors = handler.getBackgroundTasks(creds);
        if (descriptors.isEmpty()) {
            return;
        }
        TaskScope scope = TaskScope.integration(creds.getId());
        for (TaskDescriptor descriptor : descriptors) {
            taskService.upsertFromDescriptor(
                    event.connectorCode(), scope, creds.getPlatformIdentifier(), descriptor);
            log.info("Registered background task {}/{}/{} for integration {}",
                    event.connectorCode(), descriptor.taskCode(), creds.getPlatformIdentifier(), creds.getId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeleted(IntegrationDeletedEvent event) {
        int removed = taskService.deleteByScope(
                event.connectorCode(),
                TaskScope.integration(event.integrationId()));
        if (removed > 0) {
            log.info("Removed {} background task row(s) for integration {} ({})",
                    removed, event.integrationId(), event.connectorCode());
        }
    }
}
