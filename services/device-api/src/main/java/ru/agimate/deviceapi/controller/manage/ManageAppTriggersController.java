package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.ConnectorApiService;
import ru.agimate.deviceapi.service.dto.DeviceTrigger;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAppTriggersController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Triggers", description = "Manage device triggers")
public class ManageAppTriggersController {

    public static final String PATH = "/manage/app-triggers";

    private final ConnectorApiService connectorApiService;

    @Operation(
            summary = "Get all device triggers",
            description = "Returns available triggers for all user's devices"
    )
    @GetMapping("/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTriggers(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var triggers = connectorApiService.getAllConnectorTriggers(userPubId);
        return SuccessResponse.ok(triggers);
    }

    @Operation(
            summary = "Get triggers by app",
            description = "Returns available triggers for a specific app"
    )
    @GetMapping("/{appPubId}")
    public SuccessResponse<List<DeviceTrigger>> getTriggersByApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var triggers = connectorApiService.getTriggersByAppPubIdAndUser(appPubId, userPubId);
        return SuccessResponse.ok(triggers);
    }
}
