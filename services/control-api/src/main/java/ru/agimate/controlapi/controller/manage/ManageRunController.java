package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.CancelRunResponse;
import ru.agimate.controlapi.controller.manage.dto.CancelSessionResponse;
import ru.agimate.controlapi.service.trigger.RunCancellationService;

import java.util.UUID;

/**
 * Stopping runs. The only entry point for cancellation there is — ACP {@code session/cancel} and the
 * webchat stop button both come here — and deliberately a user-authenticated one: agents do not
 * cancel runs, so nothing under {@code /agent/**} and no connector tool exposes this.
 */
@RestController
@RequestMapping(ManageRunController.PATH)
@RequiredArgsConstructor
@Tag(name = "Runs", description = "Agent runs")
public class ManageRunController {

    public static final String PATH = "/manage/runs";

    private final RunCancellationService runCancellationService;

    @Operation(summary = "Stop a run",
            description = "Asks the run to stop at its next seam. Idempotent; a run that has already "
                    + "finished is not an error — the response says so.")
    @PostMapping("/{runId}/cancel")
    public SuccessResponse<CancelRunResponse> cancelRun(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID runId
    ) {
        RunCancellationService.CancelResult result =
                runCancellationService.cancelRun(runId, UUID.fromString(principal.id()));
        return new SuccessResponse<>(new CancelRunResponse(
                runId, result.status(), result.requested(), result.alreadyFinished()));
    }

    @Operation(summary = "Stop every live run of a channel session",
            description = "Stops the running one and the ones still queued behind it — the session is "
                    + "the queue's partition, so cancelling only the current run lets the next start.")
    @PostMapping("/sessions/{sessionId}/cancel")
    public SuccessResponse<CancelSessionResponse> cancelSession(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID sessionId
    ) {
        int cancelled = runCancellationService.cancelSession(sessionId, UUID.fromString(principal.id()));
        return new SuccessResponse<>(new CancelSessionResponse(sessionId, cancelled));
    }
}
