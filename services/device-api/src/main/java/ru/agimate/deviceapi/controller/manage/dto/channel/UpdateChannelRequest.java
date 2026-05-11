package ru.agimate.deviceapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

@Schema(description = "Request to update a channel")
public record UpdateChannelRequest(
        @Schema(description = "Channel display name")
        String name,

        @Schema(description = "Dot-path into trigger.data with the message text")
        String triggerMessageField,

        @Schema(description = "Reply tool params template")
        Map<String, Object> replyToolParams,

        @Schema(description = "Optional input filter (use null + clearInputFilter=true to drop)")
        Map<String, Object> inputFilter,

        @Schema(description = "Set true together with inputFilter=null to remove the existing filter")
        boolean clearInputFilter
) {}
