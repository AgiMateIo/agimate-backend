package ru.agimate.deviceapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.deviceapi.config.CentrifugoProperties;
import ru.agimate.deviceapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(ManageCentrifugoTokenController.PATH)
@Tag(name = "User Centrifugo", description = "Centrifugo tokens for user real-time events")
public class ManageCentrifugoTokenController {

    public static final String PATH = "/manage/centrifugo";

    private static final long TOKEN_EXPIRATION_SECONDS = 3600; // 1 hour

    private final CentrifugoService centrifugoService;
    private final CentrifugoProperties centrifugoProperties;

    @Operation(
            summary = "Get Centrifugo subscription token for user channel",
            description = "Returns JWT tokens for subscribing to user:{userPubId} channel with real-time events"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        String userPubId = principal.pubId();
        String channel = "user:" + userPubId;

        String connectionToken = centrifugoService.generateConnectionToken(
                userPubId, TOKEN_EXPIRATION_SECONDS
        );

        String subscriptionToken = centrifugoService.generateSubscriptionToken(
                userPubId, channel, TOKEN_EXPIRATION_SECONDS
        );

        String wsUrl = centrifugoProperties.getPublicUrl() + "/connection/websocket";

        log.debug("Generated Centrifugo tokens for user: {}, channel: {}", userPubId, channel);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl));
    }
}
