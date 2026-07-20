package ru.agimate.controlapi.controller.manage;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.IssueProbeRequest;
import ru.agimate.controlapi.controller.manage.dto.IssueProbeResponse;
import ru.agimate.controlapi.controller.manage.dto.TriggerLogAgentRunResponse;
import ru.agimate.controlapi.controller.manage.dto.TriggerLogResponse;
import ru.agimate.controlapi.database.enums.RunStatus;
import ru.agimate.controlapi.service.trigger.TriggerLogProbeService;
import ru.agimate.controlapi.service.trigger.TriggerLogService;

import java.time.LocalDateTime;
import java.util.UUID;

@RestController
@RequestMapping(ManageTriggerLogsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Trigger Logs", description = "Manage trigger logs")
public class ManageTriggerLogsController {

    public static final String PATH = "/manage/trigger-logs";

    private final TriggerLogService triggerLogService;
    private final TriggerLogProbeService triggerLogProbeService;

    @Operation(
            summary = "List trigger logs",
            description = "Returns trigger logs for the current user with optional filtering"
    )
    @GetMapping("/")
    public SuccessResponse<PageResponse<TriggerLogResponse>> getTriggerLogs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(triggerLogService.getTriggerLogs(userId, connectorCode, page, size)));
    }

    @Operation(
            summary = "List trigger runs for an agent",
            description = "Returns triggers delivered to the given agent and that agent's run of each "
                    + "(trigger_log_agents joined to trigger_logs), scoped to the current user. "
                    + "Optional filters: connectorCode, connectionId, name (substring), status."
    )
    @GetMapping("/agent-runs/")
    public SuccessResponse<PageResponse<TriggerLogAgentRunResponse>> getAgentRuns(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam UUID agentId,
            @RequestParam(required = false) String connectorCode,
            @RequestParam(required = false) String connectionId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) RunStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(triggerLogService.getAgentRuns(
                userId, agentId, connectorCode, connectionId, name, status, page, size)));
    }

    @Operation(
            summary = "Issue a discovery probe code",
            description = "Generates a random probe code to embed in a test message. With blockDelivery=true (default), matching triggers are saved to trigger_logs but NOT delivered to agents."
    )
    @PostMapping("/probe")
    public SuccessResponse<IssueProbeResponse> issueProbe(
            @RequestBody(required = false) IssueProbeRequest request
    ) {
        Boolean blockDelivery = request == null ? null : request.blockDelivery();
        return SuccessResponse.ok(triggerLogProbeService.issue(blockDelivery));
    }

    @Operation(
            summary = "Look up a trigger log by probe code",
            description = "Searches the current user's trigger logs created after `since` for a payload containing the probe code. Returns 200 + TriggerLog on the first match, or 404 if not yet found (UI polls)."
    )
    @GetMapping("/probe/match")
    public SuccessResponse<TriggerLogResponse> matchProbe(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam String code,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") LocalDateTime since
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(triggerLogProbeService.match(userId, code, since));
    }
}
