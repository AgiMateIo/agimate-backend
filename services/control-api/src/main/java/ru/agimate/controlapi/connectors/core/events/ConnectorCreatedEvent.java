package ru.agimate.controlapi.connectors.core.events;

import java.util.UUID;

/**
 * Появился новый экземпляр коннектора (например, созданы или включены integration credentials).
 *
 * @param connectorCode код коннектора
 * @param identity      идентификатор экземпляра: для integration — {@code connections.id} строкой
 * @param userId        владелец экземпляра — проставляется в {@code connector_jobs.user_id}
 */
public record ConnectorCreatedEvent(String connectorCode, String identity, UUID userId) {
}
