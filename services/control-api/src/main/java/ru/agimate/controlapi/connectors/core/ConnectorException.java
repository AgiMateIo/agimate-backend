package ru.agimate.controlapi.connectors.core;

/**
 * The only exception of the connector layer: an unknown connector, tool or job, unavailable
 * credentials, a dispatch failure. The HTTP boundary must not let it through as-is — controllers and
 * HTTP services use {@code ConnectorRegistry.findHandler(...)} with
 * {@code orElseThrow(*StatusException)}, or translate it themselves.
 *
 * <p>The message is written by the connectors' own code and is safe to show to the agent in a
 * tool result.
 */
public class ConnectorException extends RuntimeException {

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}
