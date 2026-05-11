package ru.agimate.deviceapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.ChannelSession;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Channel session (12h sliding window)")
public record ChannelSessionResponse(
        UUID pubId,
        String title,
        LocalDateTime lastMessageAt,
        LocalDateTime closedAt,
        LocalDateTime createdAt
) {
    public static ChannelSessionResponse from(ChannelSession s) {
        return new ChannelSessionResponse(
                s.getPubId(),
                s.getTitle(),
                s.getLastMessageAt(),
                s.getClosedAt(),
                s.getCreatedAt()
        );
    }
}
