package ru.agimate.controlapi.controller.app;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.config.CentrifugoProperties;
import ru.agimate.controlapi.controller.app.dto.DeviceChannelTokenRequest;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.database.entities.App;
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

    private final CentrifugoProperties centrifugoProperties;

    @Operation(
            summary = "Get Centrifugo subscription token",
            description = "Returns a JWT subscription token for the device's tools channel"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @RequestBody @Valid
            DeviceChannelTokenRequest deviceChannelTokenRequest,
            Authentication authentication,
            HttpServletRequest request
    ) {
        App app = appService.getApp(authentication);

        if (!app.isLinked() || !deviceChannelTokenRequest.deviceId().equals(app.getDeviceId())) {
            throw new ForbiddenStatusException("Device is not linked");
        }

        String deviceId = deviceChannelTokenRequest.deviceId();
        String channel = "device:" + deviceId;

        long ttl = centrifugoProperties.getTokenTtlSeconds();
        String connectionToken = centrifugoService.generateConnectionToken(deviceId, ttl);
        String subscriptionToken = centrifugoService.generateSubscriptionToken(deviceId, channel, ttl);

        String wsUrl = centrifugoProperties.getPublicUrl() + "/connection/websocket";

        log.debug("Generated Centrifugo tokens for device: {}, channel: {}, wsUrl: {}",
                deviceId, channel, wsUrl);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl));
    }
}
