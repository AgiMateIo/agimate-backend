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
@Tag(name = "Apps", description = "Manage apps and device connections")
public class ManageAppsController {

    public static final String PATH = "/manage/apps";

    private final AppService appService;

    @Operation(summary = "Get all apps for the current user")
    @GetMapping("/")
    public SuccessResponse<List<AppResponse>> getApps(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        List<App> keys = appService.getKeysForUser(userPubId);
        List<AppResponse> response = keys.stream()
                .map(AppResponse::from)
                .toList();
        return SuccessResponse.ok(response);
    }

    @Operation(summary = "Create a new app",
               description = "Creates a new API key. The key value is shown ONLY ONCE in the response. Store it securely.")
    @PostMapping("/")
    public SuccessResponse<AppCreatedResponse> createApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateAppRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        AppCreateResult result = appService.createApp(
                userPubId,
                request.name(),
                request.description()
        );
        return SuccessResponse.ok(AppCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(summary = "Get a specific app")
    @GetMapping("/{id}")
    public SuccessResponse<AppResponse> getApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        App app = appService.getKeyByPubId(id, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("App not found"));
        return SuccessResponse.ok(AppResponse.from(app));
    }

    @Operation(summary = "Update an app")
    @PutMapping("/{id}")
    public SuccessResponse<AppResponse> updateApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAppRequest request
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        App updated = appService.updateKey(
                id,
                userPubId,
                request.name(),
                request.description(),
                request.enabled()
        );
        return SuccessResponse.ok(AppResponse.from(updated));
    }

    @Operation(summary = "Delete an app (soft delete)")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> deleteApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        appService.deleteKey(id, userPubId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Regenerate an app key",
               description = "Invalidates the old key and creates a new one with the same settings")
    @PostMapping("/{id}/regenerate")
    public SuccessResponse<AppCreatedResponse> regenerateKey(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        AppCreateResult result = appService.regenerateKey(id, userPubId);
        return SuccessResponse.ok(AppCreatedResponse.from(
                result.app(),
                result.plaintextKey()
        ));
    }

    @Operation(
            summary = "Get app details",
            description = "Returns full app information including device features, triggers and tools"
    )
    @GetMapping("/{id}/detail")
    public SuccessResponse<UserAppDetailResponse> getAppDetail(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var app = appService.getAppByPubId(id, userPubId);
        return SuccessResponse.ok(UserAppDetailResponse.from(app));
    }

    @Operation(
            summary = "Disconnect device from app",
            description = "Removes device link from the specified app"
    )
    @PostMapping("/{id}/disconnect")
    public SuccessResponse<Void> disconnectApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        appService.disconnectApp(id, userPubId);
        return SuccessResponse.ok(null);
    }
}
