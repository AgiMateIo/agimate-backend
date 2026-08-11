package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentRunPromptResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentRunResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentRunTurnResponse;
import ru.agimate.controlapi.controller.manage.dto.CancelRunResponse;
import ru.agimate.controlapi.controller.manage.dto.CancelSessionResponse;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.trigger.RunCancellationService;

import java.util.UUID;

/**
 * Runs: browsing them and stopping them. Everything here is scoped to the current user — a run of
 * someone else's agent reads as absent.
 *
 * <p>Cancellation is the only entry point there is (ACP {@code session/cancel} and the webchat stop
 * button both come here) and deliberately a user-authenticated one: agents do not cancel runs, so
 * nothing under {@code /agent/**} and no connector tool exposes it.
 */
@RestController
@RequestMapping(ManageRunController.PATH)
@RequiredArgsConstructor
@Tag(name = "Runs", description = "Agent runs")
public class ManageRunController {

    public static final String PATH = "/manage/runs";

    private final RunCancellationService runCancellationService;
    private final AgentRunQueryService runQueryService;

    @Operation(summary = "List runs",
            description = "Runs of the current user's agents, newest first. Every filter is optional "
                    + "and they compose: agentId, sessionId, triggerLogId, connectorCode, connectionId, "
                    + "name (substring of the trigger's name), status.")
    @GetMapping("/")
    public SuccessResponse<PageResponse<AgentRunResponse>> listRuns(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID sessionId,
            @RequestParam(required = false) UUID triggerLogId,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return SuccessResponse.ok(PageResponse.from(runQueryService.listRuns(
                UUID.fromString(principal.id()), agentId, sessionId, triggerLogId,
                connectorCode, connectionId, name, status, page, size)));
    }

    @Operation(summary = "Get one run",
            description = "The same row the listing returns, by id. Someone else's run reads as absent.")
    @GetMapping("/{runId}")
    public SuccessResponse<AgentRunResponse> getRun(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID runId
    ) {
        return SuccessResponse.ok(runQueryService.getRun(runId, UUID.fromString(principal.id())));
    }

    @Operation(summary = "List a run's turns",
            description = "The run's transcript from the canonical ledger, newest turn first. Content "
                    + "is uncapped — tool outputs and the model's reasoning come in full.")
    @GetMapping("/{runId}/turns/")
    public SuccessResponse<PageResponse<AgentRunTurnResponse>> listTurns(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return SuccessResponse.ok(PageResponse.from(runQueryService.listTurns(
                runId, UUID.fromString(principal.id()), page, size)));
    }

    @Operation(summary = "Get a run's input",
            description = "The message list as it went into the first LLM call: system blocks, session "
                    + "history and the trigger's turn with its ephemeral blocks. Turn 0 of the ledger "
                    + "holds the question alone, so what the model was actually given is visible here only.")
    @GetMapping("/{runId}/prompt")
    public SuccessResponse<AgentRunPromptResponse> getPrompt(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID runId
    ) {
        return SuccessResponse.ok(runQueryService.getPrompt(runId, UUID.fromString(principal.id())));
    }

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
