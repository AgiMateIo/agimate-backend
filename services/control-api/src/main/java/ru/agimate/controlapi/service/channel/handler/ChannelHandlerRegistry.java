package ru.agimate.controlapi.service.channel.handler;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Collection;

/**
 * Реестр {@link ChannelHandler}-ов по {@link ChannelHandler#name()}.
 *
 * <p>Имена должны быть уникальны — дубликат это ошибка конфигурации (fail-fast на старте).
 */
@Slf4j
@Component
public class ChannelHandlerRegistry {

    private final Map<String, ChannelHandler> byName;

    public ChannelHandlerRegistry(List<ChannelHandler> handlers) {
        Map<String, ChannelHandler> map = new LinkedHashMap<>();
        for (ChannelHandler handler : handlers) {
            ChannelHandler prev = map.put(handler.name(), handler);
            if (prev != null) {
                throw new IllegalStateException("Duplicate channel handler name: " + handler.name()
                        + " (" + prev.getClass().getName() + ", " + handler.getClass().getName() + ")");
            }
        }
        this.byName = map;
        log.info("Registered {} channel handlers: {}", map.size(), map.keySet());
    }

    public Optional<ChannelHandler> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    public Collection<ChannelHandler> all() {
        return byName.values();
    }

    public ChannelHandler require(String name) {
        ChannelHandler handler = byName.get(name);
        if (handler == null) {
            throw new IllegalArgumentException("Unknown channel handler: " + name);
        }
        return handler;
    }
}
