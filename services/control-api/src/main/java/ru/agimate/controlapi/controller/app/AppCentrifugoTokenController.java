package ru.agimate.controlapi.controller.app;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.app.dto.DeviceChannelTokenRequest;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.database.entities.App;
import ru.agimate.controlapi.security.AppPrincipal;
import ru.agimate.controlapi.service.AppService;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppCentrifugoTokenController.PATH)
public class AppCentrifugoTokenController {

    public static final String PATH = AppRegistrationController.PATH + "/centrifugo";

    private final CentrifugoService centrifugoService;
    private final AppService appService;

    @Operation(
            summary = "Get Centrifugo subscription token",
            description = "Returns a JWT subscription token for the device's tools channel"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @RequestBody @Valid
            DeviceChannelTokenRequest deviceChannelTokenRequest,
            @AuthenticationPrincipal AppPrincipal principal,
            HttpServletRequest request
    ) {
        App app = appService.getApp(principal);

        if (!app.isLinked() || !deviceChannelTokenRequest.deviceId().equals(app.getDeviceId())) {
            throw new ForbiddenStatusException("Device is not linked");
        }

        String deviceId = deviceChannelTokenRequest.deviceId();
        String channel = "device:" + deviceId;

        CentrifugoTokenResponse tokens = centrifugoService.issueTokens(deviceId, channel);

        log.debug("Generated Centrifugo tokens for device: {}, channel: {}, wsUrl: {}",
                deviceId, channel, tokens.wsUrl());

        return SuccessResponse.ok(tokens);
    }
}
