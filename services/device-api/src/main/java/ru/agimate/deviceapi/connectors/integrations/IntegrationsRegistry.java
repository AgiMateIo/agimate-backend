package ru.agimate.deviceapi.connectors.integrations;

import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IntegrationsRegistry {

    private final Map<String, IntegrationHandler> handlers;

    public IntegrationsRegistry(List<IntegrationHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(IntegrationHandler::getPlatformCode, Function.identity()));
    }

    public IntegrationHandler getHandler(String platformType) {
        var handler = handlers.get(platformType);
        if (handler == null) {
            throw new BadRequestStatusException("Unsupported platform: " + platformType);
        }
        return handler;
    }

    public Collection<IntegrationHandler> getAvailablePlatforms() {
        return handlers.values();
    }
}
