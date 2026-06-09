package ru.agimate.controlapi.connectors.core;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Единый реестр коннекторов поверх всех Spring-бинов {@link ConnectorHandler}.
 *
 * <p>{@link #getHandler(String)} — для execution-путей (бросает {@link ConnectorException});
 * HTTP-граница использует {@link #findHandler(String)} и сама решает, какой
 * {@code *StatusException} бросить.
 */
@Component
public class ConnectorRegistry {

    private final Map<String, ConnectorHandler> handlers;

    public ConnectorRegistry(List<ConnectorHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ConnectorHandler::connectorCode, Function.identity()));
    }

    public ConnectorHandler getHandler(String connectorCode) {
        ConnectorHandler handler = handlers.get(connectorCode);
        if (handler == null) {
            throw new ConnectorException("Unknown connector: " + connectorCode);
        }
        return handler;
    }

    public Optional<ConnectorHandler> findHandler(String connectorCode) {
        return Optional.ofNullable(handlers.get(connectorCode));
    }

    public Optional<IntegrationConnectorHandler> findIntegrationHandler(String connectorCode) {
        return findHandler(connectorCode)
                .filter(IntegrationConnectorHandler.class::isInstance)
                .map(IntegrationConnectorHandler.class::cast);
    }

    public Collection<ConnectorHandler> getHandlers() {
        return handlers.values();
    }
}
