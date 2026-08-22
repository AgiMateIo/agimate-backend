package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgenticTeamResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgenticTeamRequest;
import ru.agimate.controlapi.controller.manage.dto.PatchAgenticTeamRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgenticTeamRequest;
import ru.agimate.controlapi.service.AgenticTeamService;

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
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agenticTeamService.getAllForUser(userId));
    }

    @Operation(summary = "Get agentic team by ID")
    @GetMapping("/{id}")
    public SuccessResponse<AgenticTeamResponse> getAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agenticTeamService.getById(id, userId));
    }

    @Operation(summary = "Create agentic team")
    @PostMapping("/")
    public SuccessResponse<AgenticTeamResponse> createAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgenticTeamRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agenticTeamService.create(userId, request));
    }

    @Operation(summary = "Update agentic team")
    @PutMapping("/{id}")
    public SuccessResponse<AgenticTeamResponse> updateAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgenticTeamRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agenticTeamService.update(id, userId, request));
    }

    @Operation(summary = "Partially update an agentic team",
            description = "Only the fields present in the body are written; a field sent as an empty "
                    + "string is cleared")
    @PatchMapping("/{id}")
    public SuccessResponse<AgenticTeamResponse> patchAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody PatchAgenticTeamRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agenticTeamService.patch(id, userId, request));
    }

    @Operation(summary = "Delete agentic team")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> deleteAgenticTeam(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        agenticTeamService.delete(id, userId);
        return SuccessResponse.empty();
    }
}
