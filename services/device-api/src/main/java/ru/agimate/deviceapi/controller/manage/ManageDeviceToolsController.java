package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.manage.dto.DeviceToolsResponse;
import ru.agimate.deviceapi.service.AppApiService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageDeviceToolsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Tools", description = "Manage device tools")
public class ManageDeviceToolsController {

    public static final String PATH = "/manage/tools";

    private final AppApiService appApiService;

    @Operation(
            summary = "Get all device tools",
            description = "Returns available tools for all user's devices"
    )
    @GetMapping("/")
    public SuccessResponse<List<DeviceToolsResponse>> getAllTools(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userPubId = UUID.fromString(principal.pubId());
        var tools = appApiService.getAllAppTools(userPubId);
        return SuccessResponse.ok(tools);
    }
}
