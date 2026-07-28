package ru.agimate.controlapi.connectors.core.events;

import java.util.UUID;

/**
 * A connector instance changed (credentials were updated, say) — its jobs are re-synced.
 *
 * @param connectorCode the connector's code
 * @param connectionId  identifier of the instance
 * @param userId        owner of the instance — written into {@code connector_jobs.user_id}
 */
public record ConnectorModifiedEvent(String connectorCode, String connectionId, UUID userId) {
}
