package ru.agimate.deviceapi.connectors.integrations;

import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class IntegrationPlatformRegistry {

    private final Map<String, IntegrationPlatformHandler> handlers;

    public IntegrationPlatformRegistry(List<IntegrationPlatformHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(IntegrationPlatformHandler::getPlatformCode, Function.identity()));
    }

    public IntegrationPlatformHandler getHandler(String platformType) {
        var handler = handlers.get(platformType);
        if (handler == null) {
            throw new BadRequestStatusException("Unsupported platform: " + platformType);
        }
        return handler;
    }
}
