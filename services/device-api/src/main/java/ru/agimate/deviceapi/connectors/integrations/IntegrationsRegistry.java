package ru.agimate.deviceapi.connectors.integrations;

import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IntegrationsRegistry {

    private final Map<String, IntegrationHandler> handlers;

    public IntegrationsRegistry(List<IntegrationHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(IntegrationHandler::getConnectorCode, Function.identity()));
    }

    public IntegrationHandler getHandler(String connectorCode) {
        var handler = handlers.get(connectorCode);
        if (handler == null) {
            throw new BadRequestStatusException("Unsupported platform: " + connectorCode);
        }
        return handler;
    }

    public Optional<IntegrationHandler> findHandler(String connectorCode) {
        return Optional.ofNullable(handlers.get(connectorCode));
    }

    public Collection<IntegrationHandler> getAvailablePlatforms() {
        return handlers.values();
    }
}
