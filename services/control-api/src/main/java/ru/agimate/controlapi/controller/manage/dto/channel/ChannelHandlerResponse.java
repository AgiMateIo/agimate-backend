package ru.agimate.controlapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Доступный обработчик канала и JSON Schema его config")
public record ChannelHandlerResponse(
        @Schema(description = "Имя handler-а (значение channelHandler при создании канала)")
        String name,
        @Schema(description = "JSON Schema (object) полей config для рендера формы")
        Map<String, Object> configFields
) {}
