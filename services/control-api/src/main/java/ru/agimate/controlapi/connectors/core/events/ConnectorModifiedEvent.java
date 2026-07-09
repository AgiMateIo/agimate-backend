package ru.agimate.controlapi.connectors.core.events;

import java.util.UUID;

/**
 * Экземпляр коннектора изменён (например, обновлены credentials) — таски пересинхронизируются.
 *
 * @param connectorCode код коннектора
 * @param connectionId  идентификатор экземпляра
 * @param userId        владелец экземпляра — проставляется в {@code connector_jobs.user_id}
 */
public record ConnectorModifiedEvent(String connectorCode, String connectionId, UUID userId) {
}
