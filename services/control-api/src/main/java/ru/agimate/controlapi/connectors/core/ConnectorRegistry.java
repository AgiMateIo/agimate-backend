package ru.agimate.controlapi.connectors.core;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single registry of connectors over every Spring {@link ConnectorHandler} bean.
 *
 * <p>{@link #getHandler(String)} is for execution paths (it throws {@link ConnectorException}); the
 * HTTP boundary uses {@link #findHandler(String)} and decides for itself which
 * {@code *StatusException} to throw. Capability interfaces ({@link ToolProvider},
 * {@link TriggerProvider}, {@link JobProvider}, {@link PromptBlockProvider}) are obtained through
 * {@link #findCapability} (listings) or {@link #capability} (execution paths, refining the role of a
 * handler already in hand).
 */
@Component
public class ConnectorRegistry {

    /**
     * Handlers are resolved lazily rather than injected as a constructor list: otherwise constructing
     * the registry drags in the construction of every {@link ConnectorHandler}, and any handler whose
     * tool service reuses a registry-dependent service (e.g. {@code platform} → {@code AgentService}
     * → {@code ConnectorRegistry}) closes a bean cycle. The graph is broken here, in the aggregator,
     * rather than with {@code @Lazy} at the consumers. The map is built once on first access (by
     * which point the context is ready).
     */
    private final ObjectProvider<ConnectorHandler> handlerProvider;
    private volatile Map<String, ConnectorHandler> handlers;

    @Autowired
    public ConnectorRegistry(ObjectProvider<ConnectorHandler> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    /** An explicit set of handlers (eager) — for tests; in the application Spring uses the ObjectProvider constructor. */
    public ConnectorRegistry(Collection<ConnectorHandler> handlers) {
        this.handlerProvider = null;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(ConnectorHandler::connectorCode, Function.identity()));
    }

    private Map<String, ConnectorHandler> handlers() {
        Map<String, ConnectorHandler> local = handlers;
        if (local == null) {
            synchronized (this) {
                local = handlers;
                if (local == null) {
                    local = handlerProvider.stream()
                            .collect(Collectors.toMap(ConnectorHandler::connectorCode, Function.identity()));
                    handlers = local;
                }
            }
        }
        return local;
    }

    public ConnectorHandler getHandler(String connectorCode) {
        ConnectorHandler handler = handlers().get(connectorCode);
        if (handler == null) {
            throw new ConnectorException("Unknown connector: " + connectorCode);
        }
        return handler;
    }

    public Optional<ConnectorHandler> findHandler(String connectorCode) {
        return Optional.ofNullable(handlers().get(connectorCode));
    }

    public Optional<IntegrationConnectorHandler> findIntegrationHandler(String connectorCode) {
        return findHandler(connectorCode)
                .filter(IntegrationConnectorHandler.class::isInstance)
                .map(IntegrationConnectorHandler.class::cast);
    }

    /**
     * The capability of a handler already in hand — for when the handler was taken from the registry
     * once and its role is being refined without a second lookup (execution paths). If the connector
     * does not implement it — {@link ConnectorException}.
     */
    public static <T> T capability(ConnectorHandler handler, Class<T> capability) {
        if (!capability.isInstance(handler)) {
            throw new ConnectorException("Connector '" + handler.connectorCode() + "' does not support "
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
        return handlers().values();
    }
}
