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
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.mobileapi.controller.dto.request.DeviceChannelTokenRequest;
import ru.agimate.mobileapi.controller.dto.response.CentrifugoTokenResponse;
import ru.agimate.mobileapi.database.entities.DeviceAuthKey;
import ru.agimate.mobileapi.service.CentrifugoService;
import ru.agimate.mobileapi.service.DeviceAuthKeyService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(DeviceCentrifugoTokenController.PATH)
public class DeviceCentrifugoTokenController {

    public static final String PATH = "/device/centrifugo";

    private static final long TOKEN_EXPIRATION_SECONDS = 3600; // 1 hour

    private final CentrifugoService centrifugoService;
    private final DeviceAuthKeyService deviceAuthKeyService;

    @Operation(
            summary = "Get Centrifugo subscription token",
            description = "Returns a JWT subscription token for the device's actions channel"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @RequestBody @Valid
            DeviceChannelTokenRequest deviceChannelTokenRequest,
            Authentication authentication
    ) {
        DeviceAuthKey deviceAuthKey = deviceAuthKeyService.getDeviceAuthKey(authentication);

        if (deviceAuthKey.getDevice() == null || !deviceChannelTokenRequest.deviceId().equals(deviceAuthKey.getDevice().getDeviceId())) {
            throw new ForbiddenStatusException("Device is not linked");
        }

        String deviceId = deviceChannelTokenRequest.deviceId();
        String channel = "device:" + deviceId + ":actions";

        String connectionToken = centrifugoService.generateConnectionToken(
                deviceId,
                TOKEN_EXPIRATION_SECONDS
        );

        String subscriptionToken = centrifugoService.generateSubscriptionToken(
                deviceId,
                channel,
                TOKEN_EXPIRATION_SECONDS
        );

        log.debug("Generated Centrifugo tokens for device: {}, channel: {}",
                deviceId, channel);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel));
    }
}
