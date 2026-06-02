package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.AgenticTeamResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgenticTeamRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgenticTeamRequest;
import ru.agimate.deviceapi.service.AgenticTeamService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgenticTeamController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agentic Teams", description = "Manage agentic teams")
public class ManageAgenticTeamController {

    public static final String PATH = "/manage/agentic-teams";

    private final AgenticTeamService agenticTeamService;

    @Operation(summary = "List agentic teams for the current user")
    @GetMapping("/")
    public SuccessResponse<List<AgenticTeamResponse>> getAgenticTeams(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agenticTeamService.getAllForUser(userPubId));
    }

    @Operation(summary = "Get agentic team by ID")
    @GetMapping("/{id}")
    public SuccessResponse<AgenticTeamResponse> getAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agenticTeamService.getById(id, userPubId));
    }

    @Operation(summary = "Create agentic team")
    @PostMapping("/")
    public SuccessResponse<AgenticTeamResponse> createAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgenticTeamRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agenticTeamService.create(userPubId, request));
    }

    @Operation(summary = "Update agentic team")
    @PutMapping("/{id}")
    public SuccessResponse<AgenticTeamResponse> updateAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgenticTeamRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agenticTeamService.update(id, userPubId, request));
    }

    @Operation(summary = "Delete agentic team")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> deleteAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agenticTeamService.delete(id, userPubId);
        return SuccessResponse.empty();
    }
}
