package ru.agimate.deviceapi.connectors.internal;

import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class ServerSideToolRegistry {

    private final Map<String, ServerSideToolHandler> handlers;

    public ServerSideToolRegistry(List<ServerSideToolHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(ServerSideToolHandler::getHandlerCode, Function.identity()));
    }

    public ServerSideToolHandler getHandler(String handlerCode) {
        var handler = handlers.get(handlerCode);
        if (handler == null) {
            throw new BadRequestStatusException("Unsupported server tool handler: " + handlerCode);
        }
        return handler;
    }

    public Collection<ServerSideToolHandler> getAvailableHandlers() {
        return handlers.values();
    }

    public ServerSideToolHandler getHandlerByToolName(String toolName) {
        String prefix = toolName.contains(".") ? toolName.substring(0, toolName.indexOf('.')) : toolName;
        return getHandler(prefix);
    }
}
