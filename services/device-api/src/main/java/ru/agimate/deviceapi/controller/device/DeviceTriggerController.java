package ru.agimate.deviceapi.controller.device;

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
import ru.agimate.deviceapi.controller.device.dto.TriggerRequest;
import ru.agimate.deviceapi.service.DeviceAuthKeyService;
import ru.agimate.deviceapi.service.TriggerLogService;
import ru.agimate.deviceapi.service.TriggerNotificationService;
import ru.agimate.deviceapi.service.TriggerRouterService;

/**
 * This Controller is used to handle requests from devices
 */
@Slf4j
@RestController
@RequestMapping(DeviceTriggerController.PATH)
@RequiredArgsConstructor
public class DeviceTriggerController {

    public static final String PATH = "/trigger";

    private final DeviceAuthKeyService deviceAuthKeyService;
    private final TriggerLogService triggerLogService;
    private final TriggerNotificationService triggerNotificationService;
    private final TriggerRouterService triggerRouterService;

    @Operation(
            summary = "Submit trigger from device",
            description = "Receives trigger event from device and publishes it for processing by webhook subscribers"
    )
    @PostMapping("/new")
    public SuccessResponse<String> submitTrigger(
            @RequestBody @Valid
            TriggerRequest triggerRequest,
            Authentication authentication
    ) {
        log.info("Trigger received - {}", triggerRequest.toString());

        var deviceAuthKey = deviceAuthKeyService.getDeviceAuthKey(authentication);

        var triggerLog = triggerLogService.logTrigger(deviceAuthKey, triggerRequest);
        triggerNotificationService.notifyTrigger(deviceAuthKey, triggerRequest);
        triggerRouterService.routeTriggerToAgents(deviceAuthKey, triggerRequest, triggerLog);

        return SuccessResponse.ok(triggerRequest.name());
    }

}
