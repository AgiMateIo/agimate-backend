package ru.agimate.controlapi.connectors.core.events;

/**
 * Экземпляр коннектора удалён или выключен — его строки в {@code connector_tasks} удаляются.
 */
public record ConnectorDeletedEvent(String connectorCode, String identity) {
}
