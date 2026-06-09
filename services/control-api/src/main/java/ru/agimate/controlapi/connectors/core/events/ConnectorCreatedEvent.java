package ru.agimate.controlapi.connectors.core.events;

/**
 * Появился новый экземпляр коннектора (например, созданы или включены integration credentials).
 *
 * @param connectorCode код коннектора
 * @param identity      идентификатор экземпляра: для integration — {@code integration_credentials.id} строкой
 */
public record ConnectorCreatedEvent(String connectorCode, String identity) {
}
