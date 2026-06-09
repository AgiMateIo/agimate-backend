package ru.agimate.controlapi.connectors.core;

/**
 * Внутренний коннектор: работает с сущностями самой платформы, без внешних credentials.
 * Маркер — отличает internal от integration при построении {@link ConnectorContext}
 * и при бутстрапе таблицы {@code connectors}.
 */
public interface InternalConnectorHandler extends ConnectorHandler {
}
