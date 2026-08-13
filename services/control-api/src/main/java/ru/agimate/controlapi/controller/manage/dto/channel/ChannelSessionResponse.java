package ru.agimate.controlapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.AgentSession;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Channel session (12h sliding window)")
public record ChannelSessionResponse(
        UUID id,
        String title,
        LocalDateTime lastMessageAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt
) {
    public static ChannelSessionResponse from(AgentSession s) {
        return new ChannelSessionResponse(
                s.getId(),
                s.getTitle(),
                s.getLastActivityAt(),
                s.getClosedAt(),
                s.getCreatedAt()
        );
    }
}
