package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.service.AppService;
import ru.agimate.deviceapi.service.dto.AppCreateResult;

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
    public SuccessResponse<List<AppResponse>> getApps(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        List<App> apps = appService.getAppsForUser(userPubId);
        List<AppResponse> response = apps.stream()
                .map(AppResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new app",
               description = "Creates a new app key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/")
    public SuccessResponse<AppCreatedResponse> createApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAppRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        AppCreateResult result = appService.createApp(
                userPubId,
                request.name(),
                request.description(),
                request.connectorCode()
        );
        return SuccessResponse.ok(AppCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get a specific app")
    @GetMapping("/{appId}")
    public SuccessResponse<AppResponse> getApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        App app = appService.getAppByPubIdForUser(appId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return SuccessResponse.ok(AppResponse.from(app));
    }

    @Operation(summary = "Update an app")
    @PutMapping("/{appId}")
    public SuccessResponse<AppResponse> updateApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId,
            @Valid @RequestBody UpdateAppRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        App updated = appService.updateApp(
                appId,
                userPubId,
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
        UUID userPubId = UUID.fromString(principal.pubId());
        appService.deleteApp(appId, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate an app key",
               description = "Invalidates the old key and creates a new one with the same settings")
    @PostMapping("/{appId}/regenerate")
    public SuccessResponse<AppCreatedResponse> regenerateKey(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        AppCreateResult result = appService.regenerateAppKey(appId, userPubId);
        return SuccessResponse.ok(AppCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(
            summary = "Get app details",
            description = "Returns full app information including device features, triggers and tools"
    )
    @GetMapping("/{appId}/detail")
    public SuccessResponse<UserAppDetailResponse> getAppDetail(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var app = appService.getAppByPubId(appId, userPubId);
        return SuccessResponse.ok(UserAppDetailResponse.from(app));
    }

    @Operation(
            summary = "Disconnect device from app",
            description = "Removes device link from the specified app"
    )
    @PostMapping("/{appId}/disconnect")
    public SuccessResponse<Void> disconnectApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        appService.disconnectApp(appId, userPubId);
        return SuccessResponse.empty();
    }
}
