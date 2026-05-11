package ru.agimate.deviceapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.Channel;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Schema(description = "Channel binding between a trigger and reply tool")
public record ChannelResponse(
        UUID pubId,
        UUID agentPubId,
        String name,
        String triggerConnectorCode,
        String triggerIdentity,
        @Schema(description = "Denormalized display name of trigger identity (App.name or IntegrationCredentials.name); null if identity is missing or deleted")
        String triggerIdentityName,
        String triggerName,
        String triggerMessageField,
        String replyConnectorCode,
        String replyIdentity,
        @Schema(description = "Denormalized display name of reply identity; null if identity is missing or deleted")
        String replyIdentityName,
        String replyToolName,
        Map<String, Object> replyToolParams,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ChannelResponse from(Channel c, String triggerIdentityName, String replyIdentityName) {
        return new ChannelResponse(
                c.getPubId(),
                c.getAgentPubId(),
                c.getName(),
                c.getTriggerConnectorCode(),
                c.getTriggerIdentity(),
                triggerIdentityName,
                c.getTriggerName(),
                c.getTriggerMessageField(),
                c.getReplyConnectorCode(),
                c.getReplyIdentity(),
                replyIdentityName,
                c.getReplyToolName(),
                c.getReplyToolParams(),
                c.getCreatedAt(),
                c.getUpdatedAt()
        );
    }

    public static ChannelResponse from(Channel c) {
        return from(c, null, null);
    }
}
