package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.*;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.dto.AppCreateResult;
import ru.agimate.controlapi.service.dto.AppTool;
import ru.agimate.controlapi.service.dto.AppTrigger;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAppsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Apps", description = "Manage apps connections")
public class ManageAppsController {

    public static final String PATH = "/manage/apps";

    private final AppService appService;

    @Operation(summary = "Get all apps for the current user")
    @GetMapping("/")
    public SuccessResponse<Page<AppResponse>> getApps(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        Page<AppResponse> response = appService.getAppsForUser(userId, page, size)
                .map(AppResponse::from);
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new app",
               description = "Creates a new app key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/")
    public SuccessResponse<AppCreatedResponse> createApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAppRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        AppCreateResult result = appService.createApp(
                userId,
                request.name(),
                request.description(),
                request.connectorCode()
        );
        return SuccessResponse.ok(AppCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(
            summary = "Get app details",
            description = "Returns full app information including device features, triggers and tools"
    )
    @GetMapping("/{appId}")
    public SuccessResponse<UserAppDetailResponse> getApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userId = UUID.fromString(principal.id());
        var app = appService.getAppById(appId, userId);
        return SuccessResponse.ok(UserAppDetailResponse.from(app));
    }

    @Operation(summary = "Update an app")
    @PutMapping("/{appId}")
    public SuccessResponse<AppResponse> updateApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId,
            @Valid @RequestBody UpdateAppRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        App updated = appService.updateApp(
                appId,
                userId,
                request.name(),
                request.description(),
                request.enabled()
        );
        return SuccessResponse.ok(AppResponse.from(updated));
    }

    @Operation(summary = "Delete an app (soft delete)")
    @DeleteMapping("/{appId}")
    public SuccessResponse<Void> deleteApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userId = UUID.fromString(principal.id());
        appService.deleteApp(appId, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Get tools of an app", description = "Returns available tools for a specific app")
    @GetMapping("/{appId}/tools/")
    public SuccessResponse<List<AppTool>> getAppTools(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(appService.getToolsByAppIdAndUser(appId, userId));
    }

    @Operation(summary = "Get triggers of an app", description = "Returns available triggers for a specific app")
    @GetMapping("/{appId}/triggers/")
    public SuccessResponse<List<AppTrigger>> getAppTriggers(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(appService.getTriggersByAppIdAndUser(appId, userId));
    }

    @Operation(summary = "Regenerate an app key",
               description = "Invalidates the old key and creates a new one. The key value is shown ONLY ONCE in the response.")
    @PostMapping("/{appId}/regenerate")
    public SuccessResponse<AppCreatedResponse> regenerateKey(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userId = UUID.fromString(principal.id());
        AppCreateResult result = appService.regenerateAppKey(appId, userId);
        return SuccessResponse.ok(AppCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }
}
