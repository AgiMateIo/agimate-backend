package ru.agimate.controlapi.controller.manage;

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
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping(ManageCentrifugoTokenController.PATH)
@Tag(name = "User Centrifugo", description = "Centrifugo tokens for user real-time events")
public class ManageCentrifugoTokenController {

    public static final String PATH = "/manage/centrifugo";

    private final CentrifugoService centrifugoService;

    @Operation(
            summary = "Get Centrifugo subscription token for user channel",
            description = "Returns JWT tokens for subscribing to user:{userId} channel with real-time events"
    )
    @PostMapping("/token")
    public SuccessResponse<CentrifugoTokenResponse> getSubscriptionToken(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        String userId = principal.id();
        String channel = "user:" + userId;
        log.debug("Generated Centrifugo tokens for user: {}, channel: {}", userId, channel);
        return SuccessResponse.ok(centrifugoService.issueTokens(userId, channel));
    }
}
