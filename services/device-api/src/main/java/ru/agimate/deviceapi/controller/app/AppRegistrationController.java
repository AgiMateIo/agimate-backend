package ru.agimate.deviceapi.controller.app;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.deviceapi.controller.app.dto.LinkDeviceRequest;
import ru.agimate.deviceapi.service.ConnectorService;

@Slf4j
@RestController
@RequestMapping(AppRegistrationController.PATH)
@RequiredArgsConstructor
public class AppRegistrationController {

    public static final String PATH = "/registration";

    private final ConnectorService connectorService;

    @Operation(
            summary = "Link device to connector",
            description = "Associates device information with the authenticated connector"
    )
    @PostMapping("/link")
    public SuccessResponse<String> linkDevice(
            @RequestBody @Valid
            LinkDeviceRequest linkDeviceRequest,
            Authentication authentication
    ) {
        log.info("Link device - deviceId {}", linkDeviceRequest.deviceId());

        var connector = connectorService.linkDevice(authentication, linkDeviceRequest);

        if (connector == null) {
            throw new ConflictStatusException("Can't link this device. This connector key is probably already in use");
        }

        return SuccessResponse.ok("success");
    }

}
