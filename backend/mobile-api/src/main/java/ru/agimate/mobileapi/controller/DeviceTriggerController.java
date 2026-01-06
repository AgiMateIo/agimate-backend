package ru.agimate.mobileapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.mobileapi.controller.dto.request.TriggerRequest;
import ru.agimate.mobileapi.service.DeviceAuthKeyService;
import ru.agimate.mobileapi.service.TriggerEventPublisher;
import ru.agimate.mobileapi.service.dto.DeviceTriggerEvent;

/**
 * This Controller is used to handle requests from mobile devices
 */
@Slf4j
@RestController
@RequestMapping(DeviceTriggerController.PATH)
@RequiredArgsConstructor
public class DeviceTriggerController {

    public static final String PATH = "/device/trigger";

    private final TriggerEventPublisher triggerEventPublisher;
    private final DeviceAuthKeyService deviceAuthKeyService;

    @Operation(summary = "Handle trigger from device")
    @PostMapping("/new")
    public SuccessResponse<String> trigger(
            @RequestBody @Valid
            TriggerRequest triggerRequest,
            Authentication authentication
    ) {
        log.info("Trigger received - {} from device: {} (user: {})",
                triggerRequest.name(),
                triggerRequest.deviceId(),
                triggerRequest.userId());

        var deviceAuthKey = deviceAuthKeyService.getDeviceAuthKey();
        triggerEventPublisher.publish(new DeviceTriggerEvent(deviceAuthKey, triggerRequest));

        return SuccessResponse.ok(triggerRequest.name());
    }

}
