package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.integrations.IntegrationHandler;
import ru.agimate.controlapi.connectors.integrations.IntegrationsRegistry;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.enums.ConnectorTaskScopeKind;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;

import java.util.Optional;

/**
 * Резолвит {@link Task} для строк {@code connector_tasks} со scope
 * {@link ConnectorTaskScopeKind#INTEGRATION}. Возвращает {@link Optional#empty()} для всех
 * остальных scope'ов — другой resolver (например, {@code InternalTaskResolver} в шаге 4)
 * подхватит их.
 *
 * <p>На каждом срабатывании заново загружает credentials из БД (новый токен подхватится без
 * рестарта) и заново вызывает {@code handler.getBackgroundTasks(creds)} — это дёшево
 * (просто построение лямбды), а closure захватывает свежий {@code creds}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntegrationTaskResolver implements TaskResolver {

    private final IntegrationsRegistry integrationsRegistry;
    private final IntegrationCredentialsRepository credentialsRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<Task> resolve(ConnectorTask row) {
        if (row.getScopeKind() != ConnectorTaskScopeKind.INTEGRATION) {
            return Optional.empty();
        }
        IntegrationCredentials creds = credentialsRepository.findByIdNotDeleted(row.getScopeId()).orElse(null);
        if (creds == null) {
            log.debug("Integration {} not found (or deleted) — cannot resolve task {}",
                    row.getScopeId(), row.getTaskCode());
            return Optional.empty();
        }
        IntegrationHandler handler = integrationsRegistry.findHandler(row.getConnectorCode()).orElse(null);
        if (handler == null) {
            log.warn("No handler for connector {} — cannot resolve task {}",
                    row.getConnectorCode(), row.getTaskCode());
            return Optional.empty();
        }
        return handler.getBackgroundTasks(creds).stream()
                .filter(d -> row.getTaskCode().equals(d.taskCode()))
                .map(TaskDescriptor::task)
                .findFirst();
    }
}
