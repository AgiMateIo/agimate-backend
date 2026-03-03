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
import ru.agimate.deviceapi.config.CentrifugoProperties;
import ru.agimate.deviceapi.controller.app.dto.DeviceChannelTokenRequest;
import ru.agimate.deviceapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.service.ConnectorService;
import ru.agimate.deviceapi.service.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppCentrifugoTokenController.PATH)
public class AppCentrifugoTokenController {

    public static final String PATH = AppRegistrationController.PATH + "/centrifugo";

    private static final long TOKEN_EXPIRATION_SECONDS = 3600; // 1 hour

    private final CentrifugoService centrifugoService;
    private final ConnectorService connectorService;

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
        Connector connector = connectorService.getConnector(authentication);

        if (!connector.isLinked() || !deviceChannelTokenRequest.deviceId().equals(connector.getDeviceId())) {
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

        String wsUrl = centrifugoProperties.getPublicUrl() + "/connection/websocket";

        log.debug("Generated Centrifugo tokens for device: {}, channel: {}, wsUrl: {}",
                deviceId, channel, wsUrl);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl));
    }
}
