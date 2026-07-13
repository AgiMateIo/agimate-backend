package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.AgentPresetResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentPresetRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentPresetRequest;
import ru.agimate.controlapi.service.AgentPresetService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentPresetController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent presets", description = "Role presets for the agent creation wizard")
public class ManageAgentPresetController {

    public static final String PATH = "/manage/agent-presets";

    private final AgentPresetService agentPresetService;

    @Operation(summary = "List enabled agent role presets (gallery)")
    @GetMapping("/")
    public SuccessResponse<List<AgentPresetResponse>> getPresets() {
        return SuccessResponse.ok(agentPresetService.list());
    }

    @Operation(summary = "List all presets including disabled. ADMIN only")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/all/")
    public SuccessResponse<List<AgentPresetResponse>> getAllPresets() {
        return SuccessResponse.ok(agentPresetService.listAll());
    }

    @Operation(summary = "Create an agent role preset. ADMIN only")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/")
    public SuccessResponse<AgentPresetResponse> createPreset(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgentPresetRequest request
    ) {
        UUID actorId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentPresetService.create(actorId, request));
    }

    @Operation(summary = "Update an agent role preset (partial; code is immutable). ADMIN only")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}")
    public SuccessResponse<AgentPresetResponse> updatePreset(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAgentPresetRequest request
    ) {
        UUID actorId = UUID.fromString(principal.id());
        return SuccessResponse.ok(agentPresetService.update(actorId, id, request));
    }
}
