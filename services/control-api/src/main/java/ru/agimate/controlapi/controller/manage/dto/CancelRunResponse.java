package ru.agimate.controlapi.controller.manage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.database.enums.RunStatus;

import java.util.UUID;

@Schema(description = "Outcome of asking a run to stop")
public record CancelRunResponse(
        @Schema(description = "Run ID")
        UUID runId,

        @Schema(description = "The run's status at the moment of the request; the terminal one arrives "
                + "later, when the run reaches its next seam")
        RunStatus status,

        @Schema(description = "This call recorded the request (false for a repeat press or a finished run)")
        boolean requested,

        @Schema(description = "The run had already finished — nothing was stopped, and the user must be "
                + "told rather than shown a fake «stopped»")
        boolean alreadyFinished
) {
}
