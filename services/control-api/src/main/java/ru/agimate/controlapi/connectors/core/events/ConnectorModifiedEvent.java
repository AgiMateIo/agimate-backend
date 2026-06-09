package ru.agimate.controlapi.connectors.core.events;

/**
 * Экземпляр коннектора изменён (например, обновлены credentials) — таски пересинхронизируются.
 */
public record ConnectorModifiedEvent(String connectorCode, String identity) {
}
