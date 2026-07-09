package ru.agimate.controlapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

@Schema(description = "Request to create a channel")
public record CreateChannelRequest(
        @NotNull
        @Schema(description = "Agent public ID")
        UUID agentId,

        @NotBlank
        @Schema(description = "Channel display name")
        String name,

        @NotBlank
        @Schema(description = "Channel handler name (e.g. 'generic')")
        String channelHandler,

        @NotBlank
        @Schema(description = "Trigger source connector code")
        String connectorCode,

        @NotBlank
        @Schema(description = "Connector instance id (connections.id)")
        String connectionId,

        @NotNull
        @Schema(description = "Handler-specific configuration (settings map)")
        Map<String, Object> config,

        @Schema(description = "Optional input filter (dot-path → value) applied to trigger.data")
        Map<String, Object> inputFilter
) {}
