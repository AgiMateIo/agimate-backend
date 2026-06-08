package ru.agimate.controlapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Single channel session message")
public record ChannelSessionMessageResponse(
        UUID id,
        String direction,
        String message,
        LocalDateTime createdAt
) {
    public static ChannelSessionMessageResponse from(ChannelSessionMessage m) {
        return new ChannelSessionMessageResponse(
                m.getId(),
                m.getTriggerInput() != null ? "IN" : "OUT",
                m.getMessage(),
                m.getCreatedAt()
        );
    }
}
