package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.DeviceToolsResponse;
import ru.agimate.deviceapi.service.ConnectorApiService;

import ru.agimate.deviceapi.service.dto.DeviceTool;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageAppToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Tools", description = "Manage device tools")
public class ManageAppToolsController {

    public static final String PATH = "/manage/app-tools";

    private final ConnectorApiService connectorApiService;

    @Operation(
            summary = "Get all device tools",
            description = "Returns available tools for all user's devices"
    )
    @GetMapping("/")
    public SuccessResponse<List<DeviceToolsResponse>> getAllTools(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var tools = connectorApiService.getAllConnectorTools(userPubId);
        return SuccessResponse.ok(tools);
    }

    @Operation(
            summary = "Get tools by app",
            description = "Returns available tools for a specific app"
    )
    @GetMapping("/app/{appPubId}")
    public SuccessResponse<List<DeviceTool>> getToolsByApp(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID appPubId
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var tools = connectorApiService.getToolsByAppPubIdAndUser(appPubId, userPubId);
        return SuccessResponse.ok(tools);
    }
}
