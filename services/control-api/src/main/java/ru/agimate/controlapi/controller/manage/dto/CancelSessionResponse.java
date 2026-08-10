package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

@Schema(description = "Outcome of stopping a whole conversation")
public record CancelSessionResponse(
        @Schema(description = "Channel session ID")
        UUID sessionId,

        @Schema(description = "How many live runs the stop request was recorded for — the running one "
                + "plus everything still queued behind it")
        int cancelled
) {
}
