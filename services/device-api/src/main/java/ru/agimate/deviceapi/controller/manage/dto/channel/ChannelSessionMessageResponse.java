package ru.agimate.deviceapi.controller.manage.dto.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Single channel session message")
public record ChannelSessionMessageResponse(
        UUID pubId,
        String direction,
        String message,
        LocalDateTime createdAt
) {
    public static ChannelSessionMessageResponse from(ChannelSessionMessage m) {
        return new ChannelSessionMessageResponse(
                m.getPubId(),
                m.getTriggerInput() != null ? "IN" : "OUT",
                m.getMessage(),
                m.getCreatedAt()
        );
    }
}
