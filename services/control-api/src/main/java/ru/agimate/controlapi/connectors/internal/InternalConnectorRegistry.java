package ru.agimate.controlapi.connectors.internal;

import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class InternalConnectorRegistry {

    private final Map<String, InternalConnectorHandler> handlers;

    public InternalConnectorRegistry(List<InternalConnectorHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(InternalConnectorHandler::getConnectorCode, Function.identity()));
    }

    public InternalConnectorHandler getHandler(String connectorCode) {
        var handler = handlers.get(connectorCode);
        if (handler == null) {
            throw new BadRequestStatusException("Unsupported internal connector: " + connectorCode);
        }
        return handler;
    }

    public Collection<InternalConnectorHandler> getAvailableHandlers() {
        return handlers.values();
    }

    public InternalConnectorHandler getHandlerByToolName(String toolName) {
        String prefix = toolName.contains(".") ? toolName.substring(0, toolName.indexOf('.')) : toolName;
        return getHandler(prefix);
    }
}
