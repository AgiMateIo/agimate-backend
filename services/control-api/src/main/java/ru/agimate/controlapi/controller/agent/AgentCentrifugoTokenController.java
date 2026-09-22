package ru.agimate.controlapi.controller.agent;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.security.AgentPrincipal;
import ru.agimate.controlapi.realtime.CentrifugoTokens;
import ru.agimate.controlapi.realtime.RealtimeChannels;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(AgentCentrifugoTokenController.PATH)
@Tag(name = "Agent Centrifugo", description = "Centrifugo tokens for agents")
public class AgentCentrifugoTokenController {

    public static final String PATH = AgentController.PATH + "/centrifugo";

    private final CentrifugoTokens centrifugoTokens;

    @Operation(
            summary = "Get Centrifugo subscription token for agent",
            description = "Returns a JWT subscription token for the agent's channel"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @AuthenticationPrincipal AgentPrincipal principal
    ) {
        String agentId = principal.agentId().toString();
        String channel = RealtimeChannels.agent(principal.agentId());
        log.debug("Generated Centrifugo tokens for agent: {}, channel: {}", agentId, channel);
        return SuccessResponse.ok(centrifugoTokens.issueTokens(agentId, channel));
    }
}
