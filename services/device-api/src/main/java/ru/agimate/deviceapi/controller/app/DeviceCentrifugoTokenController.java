package ru.agimate.deviceapi.controller.app;

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
import ru.agimate.deviceapi.controller.app.dto.DeviceChannelTokenRequest;
import ru.agimate.deviceapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.service.AppService;
import ru.agimate.deviceapi.service.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(DeviceCentrifugoTokenController.PATH)
public class DeviceCentrifugoTokenController {

    public static final String PATH = "/centrifugo";

    private static final long TOKEN_EXPIRATION_SECONDS = 3600; // 1 hour

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
            Authentication authentication,
            HttpServletRequest request
    ) {
        App app = appService.getApp(authentication);

        if (!app.isLinked() || !deviceChannelTokenRequest.deviceId().equals(app.getDeviceId())) {
            throw new ForbiddenStatusException("Device is not linked");
        }

        String deviceId = deviceChannelTokenRequest.deviceId();
        String channel = "device:" + deviceId;

        String connectionToken = centrifugoService.generateConnectionToken(
                deviceId,
                TOKEN_EXPIRATION_SECONDS
        );

        String subscriptionToken = centrifugoService.generateSubscriptionToken(
                deviceId,
                channel,
                TOKEN_EXPIRATION_SECONDS
        );

        String wsScheme = "https".equals(request.getScheme()) ? "wss" : "ws";
        String wsHost = request.getServerName().replaceFirst("^api\\.", "centrifugo.");
        String wsUrl = wsScheme + "://" + wsHost + "/connection/websocket";

        log.debug("Generated Centrifugo tokens for device: {}, channel: {}",
                deviceId, channel);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl));
    }
}
