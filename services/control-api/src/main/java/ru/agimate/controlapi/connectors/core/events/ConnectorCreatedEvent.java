package ru.agimate.controlapi.connectors.core.events;

import java.util.UUID;

/**
 * A new connector instance has appeared (integration credentials were created or enabled, say).
 *
 * @param connectorCode the connector's code
 * @param connectionId  identifier of the instance — {@code connections.id} as a string
 * @param userId        owner of the instance — written into {@code connector_jobs.user_id}
 */
public record ConnectorCreatedEvent(String connectorCode, String connectionId, UUID userId) {
}
