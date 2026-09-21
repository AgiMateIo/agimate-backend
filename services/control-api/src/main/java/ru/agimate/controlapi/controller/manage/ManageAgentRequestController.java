package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.PageResponse;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.agentrequest.AgentRequestResponse;
import ru.agimate.controlapi.service.team.AgentRequestQueryService;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the agents of a team ask of each other. A sibling of the team's board: the board shows work
 * the user handed out, this shows work the agents handed to one another — the two connectors are
 * independent, and a team may have either, both or neither.
 */
@RestController
@RequestMapping(ManageAgentRequestController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent requests", description = "Requests between the agents of a team")
public class ManageAgentRequestController {

    public static final String PATH = ManageAgenticTeamController.PATH + "/{teamId}/requests";

    private final AgentRequestQueryService agentRequestQueryService;

    @Operation(summary = "List the team's requests, freshest activity first",
            description = "agentId matches either side; fromAgentId and toAgentId pick one. The status "
                    + "is derived from the thread's runs, so it is not a filter — a page carries it "
                    + "for every row instead")
    @GetMapping("/")
    public SuccessResponse<PageResponse<AgentRequestResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID teamId,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(required = false) UUID fromAgentId,
            @RequestParam(required = false) UUID toAgentId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(PageResponse.from(agentRequestQueryService.list(
                userId, teamId, agentId, fromAgentId, toAgentId, since, page, size)));
    }

    @Operation(summary = "One request with its exchange",
            description = "Requests and reports of the thread in the order they happened; each carries "
                    + "the callee's runId for GET /manage/runs/{runId}/turns/")
    @GetMapping("/{threadId}")
    public SuccessResponse<AgentRequestDetailResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID teamId,
            @PathVariable UUID threadId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentRequestQueryService.get(userId, teamId, threadId));
    }
}
