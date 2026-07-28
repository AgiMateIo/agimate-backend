package ru.agimate.controlapi.connectors.core.events;

/**
 * A connector instance was deleted or disabled — its {@code connector_jobs} rows are removed.
 */
public record ConnectorDeletedEvent(String connectorCode, String connectionId) {
}
