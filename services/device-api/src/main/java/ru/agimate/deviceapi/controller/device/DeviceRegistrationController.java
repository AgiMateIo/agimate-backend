package ru.agimate.deviceapi.controller.device;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.deviceapi.controller.device.dto.LinkDeviceRequest;
import ru.agimate.deviceapi.service.DeviceAuthKeyService;

/**
 * This Controller is used to handle requests from devices
 */
@Slf4j
@RestController
@RequestMapping(DeviceRegistrationController.PATH)
@RequiredArgsConstructor
public class DeviceRegistrationController {

    public static final String PATH = "/registration";

    private final DeviceAuthKeyService deviceAuthKeyService;

    @Operation(
            summary = "Link device to auth key",
            description = "Associates device information (deviceId, deviceName, deviceOs) with the authenticated device auth key"
    )
    @PostMapping("/link")
    public SuccessResponse<String> linkDevice(
            @RequestBody @Valid
            LinkDeviceRequest linkDeviceRequest,
            Authentication authentication
    ) {
        log.info("Link device - {}, deviceId {}, os '{}'",
                linkDeviceRequest.deviceName(),
                linkDeviceRequest.deviceId(),
                linkDeviceRequest.deviceOs());

        var device = deviceAuthKeyService.linkDevice(authentication, linkDeviceRequest);

        if (device == null) {
            throw new ConflictStatusException("Can't link this device. This device key is probably already in use");
        }

        return SuccessResponse.ok("success");
    }

}
