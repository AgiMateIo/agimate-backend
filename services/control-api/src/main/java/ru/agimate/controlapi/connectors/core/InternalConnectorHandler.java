package ru.agimate.controlapi.connectors.core;

/**
 * An internal connector: it works with the platform's own entities and needs no external
 * credentials. A marker — it distinguishes internal from integration when building a
 * {@link ConnectorEnv} and when bootstrapping the {@code connectors} table.
 */
public interface InternalConnectorHandler extends ConnectorHandler {
}
