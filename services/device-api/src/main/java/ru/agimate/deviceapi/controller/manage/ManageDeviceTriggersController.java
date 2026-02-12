package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.controller.dto.response.DeviceTriggersResponse;
import ru.agimate.deviceapi.service.InternalDeviceApiService;

import java.util.List;

@RestController
@RequestMapping(ManageDeviceTriggersController.PATH)
@RequiredArgsConstructor
@Tag(name = "Device Triggers", description = "Manage device triggers")
public class ManageDeviceTriggersController {

    public static final String PATH = "/manage/triggers";

    private final InternalDeviceApiService internalDeviceApiService;

    @Operation(
            summary = "Get all device triggers",
            description = "Returns available triggers for all user's devices"
    )
    @GetMapping("/")
    public SuccessResponse<List<DeviceTriggersResponse>> getAllTriggers(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        var triggers = internalDeviceApiService.getAllTriggers(principal.pubId());
        return SuccessResponse.ok(triggers);
    }
}
