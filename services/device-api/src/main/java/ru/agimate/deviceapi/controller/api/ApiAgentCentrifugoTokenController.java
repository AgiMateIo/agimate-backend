package ru.agimate.deviceapi.controller.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import ru.agimate.common.security.apikey.ApiKeyPrincipal;
import ru.agimate.deviceapi.controller.api.dto.AgentCentrifugoTokenRequest;
import ru.agimate.deviceapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.deviceapi.service.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(ApiAgentCentrifugoTokenController.PATH)
@Tag(name = "Agent Centrifugo", description = "Centrifugo tokens for agents")
public class ApiAgentCentrifugoTokenController {

    public static final String PATH = ApiConnectorsController.PATH + "/centrifugo";

    private static final long TOKEN_EXPIRATION_SECONDS = 3600; // 1 hour

    private final CentrifugoService centrifugoService;

    @Operation(
            summary = "Get Centrifugo subscription token for agent",
            description = "Returns a JWT subscription token for the agent's channel"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @RequestBody @Valid AgentCentrifugoTokenRequest request,
            @AuthenticationPrincipal ApiKeyPrincipal principal,
            HttpServletRequest httpRequest
    ) {
        String apiKeyPubId = request.apiKeyPubId().toString();
        String channel = "agent:" + apiKeyPubId;

        String connectionToken = centrifugoService.generateConnectionToken(
                apiKeyPubId,
                TOKEN_EXPIRATION_SECONDS
        );

        String subscriptionToken = centrifugoService.generateSubscriptionToken(
                apiKeyPubId,
                channel,
                TOKEN_EXPIRATION_SECONDS
        );

        String wsScheme = "https".equals(httpRequest.getScheme()) ? "wss" : "ws";
        String wsHost = httpRequest.getServerName().replaceFirst("^api\\.", "centrifugo.");
        String wsUrl = wsScheme + "://" + wsHost + "/connection/websocket";

        log.debug("Generated Centrifugo tokens for agent: {}, channel: {}",
                apiKeyPubId, channel);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl));
    }
}
