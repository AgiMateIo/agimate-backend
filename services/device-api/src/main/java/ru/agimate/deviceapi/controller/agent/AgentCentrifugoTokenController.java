package ru.agimate.deviceapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.deviceapi.config.CentrifugoProperties;
import ru.agimate.deviceapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.deviceapi.security.AgentPrincipal;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AgentCentrifugoTokenController.PATH)
@Tag(name = "Agent Centrifugo", description = "Centrifugo tokens for agents")
public class AgentCentrifugoTokenController {

    public static final String PATH = AgentController.PATH + "/centrifugo";

    private static final long TOKEN_EXPIRATION_SECONDS = 3600; // 1 hour

    private final CentrifugoService centrifugoService;

    private final CentrifugoProperties centrifugoProperties;

    @Operation(
            summary = "Get Centrifugo subscription token for agent",
            description = "Returns a JWT subscription token for the agent's channel"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        String agentId = principal.agentPubId().toString();
        String channel = "agent:" + agentId;

        String connectionToken = centrifugoService.generateConnectionToken(
                agentId,
                TOKEN_EXPIRATION_SECONDS
        );

        String subscriptionToken = centrifugoService.generateSubscriptionToken(
                agentId,
                channel,
                TOKEN_EXPIRATION_SECONDS
        );

        String wsUrl = centrifugoProperties.getPublicUrl() + "/connection/websocket";

        log.debug("Generated Centrifugo tokens for agent: {}, channel: {}",
                agentId, channel);

        return SuccessResponse.ok(new CentrifugoTokenResponse(connectionToken, subscriptionToken, channel, wsUrl));
    }
}
