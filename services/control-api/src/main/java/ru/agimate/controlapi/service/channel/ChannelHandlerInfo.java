package ru.agimate.controlapi.service.channel;

import java.util.Map;

/**
 * A channel handler the platform supports and the JSON Schema of the config fields it accepts.
 * Service-layer view — the controller analog {@code ChannelHandlerResponse} stays out of the
 * connector layer, which consumes this record through {@link ChannelService#listHandlersFlat()}.
 */
public record ChannelHandlerInfo(String name, Map<String, Object> configFields) {
}
