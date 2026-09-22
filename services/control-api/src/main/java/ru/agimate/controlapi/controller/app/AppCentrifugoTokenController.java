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
import ru.agimate.controlapi.realtime.CentrifugoTokens;
import ru.agimate.controlapi.realtime.RealtimeChannels;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AppCentrifugoTokenController.PATH)
public class AppCentrifugoTokenController {

    public static final String PATH = AppRegistrationController.PATH + "/centrifugo";

    private final CentrifugoTokens centrifugoTokens;
    private final AppService appService;

    @Operation(
            summary = "Get Centrifugo subscription token",
            description = "Returns a JWT subscription token for the app's tools channel"
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

        // The channel and the token's subject go by app.id (= connectionId) rather than by the client's
        // device_id: device_id is not unique across tenants. The device subscribes to the channel returned here.
        String channel = RealtimeChannels.app(app.getId());

        CentrifugoTokenResponse tokens = centrifugoTokens.issueTokens(app.getId().toString(), channel);

        log.debug("Generated Centrifugo tokens for app: {}, channel: {}, wsUrl: {}",
                app.getId(), channel, tokens.wsUrl());

        return SuccessResponse.ok(tokens);
    }
}
