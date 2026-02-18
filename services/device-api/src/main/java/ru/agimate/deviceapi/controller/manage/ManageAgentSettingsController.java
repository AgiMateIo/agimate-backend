package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.AgentSettingsResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentSettingsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentSettingsRequest;
import ru.agimate.deviceapi.service.AgentSettingsService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAgentSettingsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Agent Settings", description = "Manage agent settings")
public class ManageAgentSettingsController {

    public static final String PATH = "/manage/agent-settings";

    private final AgentSettingsService agentSettingsService;

    @Operation(summary = "List agent settings for the current user")
    @GetMapping("/")
    public SuccessResponse<List<AgentSettingsResponse>> getAgentSettings(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSettingsService.getAllForUser(userPubId));
    }

    @Operation(summary = "Create agent settings")
    @PostMapping("/")
    public SuccessResponse<AgentSettingsResponse> createAgentSettings(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAgentSettingsRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSettingsService.create(userPubId, request));
    }

    @Operation(summary = "Get agent settings by API key")
    @GetMapping("/{apiKeyPubId}")
    public SuccessResponse<AgentSettingsResponse> getAgentSettings(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID apiKeyPubId
    ) {
        return SuccessResponse.ok(agentSettingsService.getByApiKeyPubId(apiKeyPubId));
    }

    @Operation(summary = "Update agent settings")
    @PutMapping("/{apiKeyPubId}")
    public SuccessResponse<AgentSettingsResponse> updateAgentSettings(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID apiKeyPubId,
            @Valid @RequestBody UpdateAgentSettingsRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        return SuccessResponse.ok(agentSettingsService.update(apiKeyPubId, userPubId, request));
    }

    @Operation(summary = "Delete agent settings")
    @DeleteMapping("/{apiKeyPubId}")
    public SuccessResponse<Void> deleteAgentSettings(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID apiKeyPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        agentSettingsService.delete(apiKeyPubId, userPubId);
        return SuccessResponse.empty();
    }
}
