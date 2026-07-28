package ru.agimate.controlapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "An available channel handler and the JSON Schema of its config")
public record ChannelHandlerResponse(
        @Schema(description = "Handler name (the channelHandler value when creating a channel)")
        String name,
        @Schema(description = "JSON Schema (object) of the config fields, for rendering the form")
        Map<String, Object> configFields
) {}
