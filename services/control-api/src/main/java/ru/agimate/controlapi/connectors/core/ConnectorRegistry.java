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
 * {@code *StatusException} бросить. Capability-интерфейсы ({@link ToolProvider},
 * {@link TriggerProvider}, {@link JobProvider}, {@link PromptBlockProvider}) достаются через
 * {@link #getCapability}/{@link #findCapability} по тем же правилам.
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

    /** Capability коннектора для execution-путей: нет коннектора или capability — {@link ConnectorException}. */
    public <T> T getCapability(String connectorCode, Class<T> capability) {
        ConnectorHandler handler = getHandler(connectorCode);
        if (!capability.isInstance(handler)) {
            throw new ConnectorException("Connector '" + connectorCode + "' does not support "
                    + capability.getSimpleName());
        }
        return capability.cast(handler);
    }

    public <T> Optional<T> findCapability(String connectorCode, Class<T> capability) {
        return findHandler(connectorCode)
                .filter(capability::isInstance)
                .map(capability::cast);
    }

    public Collection<ConnectorHandler> getHandlers() {
        return handlers.values();
    }
}
