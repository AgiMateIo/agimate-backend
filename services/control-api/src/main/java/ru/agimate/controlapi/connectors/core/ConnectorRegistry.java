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
 * Единый реестр коннекторов поверх всех Spring-бинов {@link ConnectorHandler}.
 *
 * <p>{@link #getHandler(String)} — для execution-путей (бросает {@link ConnectorException});
 * HTTP-граница использует {@link #findHandler(String)} и сама решает, какой
 * {@code *StatusException} бросить. Capability-интерфейсы ({@link ToolProvider},
 * {@link TriggerProvider}, {@link JobProvider}, {@link PromptBlockProvider}) достаются через
 * {@link #findCapability} (листинги) или {@link #capability} (execution-пути,
 * уточнение роли уже полученного handler'а).
 */
@Component
public class ConnectorRegistry {

    /**
     * Хендлеры резолвятся лениво, а не инжектятся списком в конструктор: иначе конструирование реестра
     * тянет за собой конструирование всех {@link ConnectorHandler}, и любой хендлер, чей tool-сервис
     * переиспользует registry-зависимый сервис (например {@code platform} → {@code AgentService} →
     * {@code ConnectorRegistry}), замыкает цикл бинов. Разрыв графа — здесь, в агрегаторе, а не {@code @Lazy}
     * у потребителей. Карта строится однажды при первом обращении (контекст к тому моменту готов).
     */
    private final ObjectProvider<ConnectorHandler> handlerProvider;
    private volatile Map<String, ConnectorHandler> handlers;

    @Autowired
    public ConnectorRegistry(ObjectProvider<ConnectorHandler> handlerProvider) {
        this.handlerProvider = handlerProvider;
    }

    /** Явный набор хендлеров (eager) — для тестов; в приложении Spring использует ObjectProvider-конструктор. */
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
     * Capability уже полученного handler'а — когда handler из registry взят один раз и его роль
     * уточняется без повторного lookup'а (execution-пути). Коннектор её не реализует —
     * {@link ConnectorException}.
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
