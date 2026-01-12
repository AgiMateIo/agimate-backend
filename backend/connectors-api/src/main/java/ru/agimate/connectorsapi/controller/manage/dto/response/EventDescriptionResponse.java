package ru.agimate.connectorsapi.controller.manage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.connectorsapi.database.entities.EventDescription;

@Schema(description = "Event description information")
public record EventDescriptionResponse(
        @Schema(description = "Event description ID")
        Long id,

        @Schema(description = "Event type identifier", example = "ozon.order.created")
        String eventType,

        @Schema(description = "Human-readable title", example = "Новый заказ Ozon")
        String title,

        @Schema(description = "Detailed description of the event")
        String description
) {
    public static EventDescriptionResponse from(EventDescription event) {
        return new EventDescriptionResponse(
                event.getId(),
                event.getEventType(),
                event.getTitle(),
                event.getDescription()
        );
    }
}
