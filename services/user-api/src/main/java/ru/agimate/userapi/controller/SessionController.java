package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.controller.dto.response.auth.SessionResponse;
import ru.agimate.userapi.database.entities.PushSubscription;
import ru.agimate.userapi.service.auth.AuthSessionService;
import ru.agimate.userapi.service.push.PushSubscriptionService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's own signed-in devices. What the session registry buys beyond making logout real: a
 * mobile session lasts for months, so seeing where one is signed in — and ending one of them from
 * somewhere else — stops being hygiene and becomes a feature people look for.
 *
 * <p>Deliberately outside {@code /user/**} to avoid the {@code /user/user/…} wart; the security
 * chain names {@code /sessions/**} explicitly instead, GUEST included — an account still awaiting
 * approval that lost a phone has the most reason to be here.
 */
@RestController
@RequestMapping("/sessions")
@RequiredArgsConstructor
@Tag(name = "Sessions", description = "Signed-in devices of the current user")
public class SessionController {

    private final AuthSessionService authSessionService;
    private final PushSubscriptionService pushSubscriptionService;

    @Operation(summary = "List my active sessions",
            description = "One entry per signed-in device, most recently seen first, each with what it "
                    + "is subscribed to notifications with")
    @GetMapping("/")
    public SuccessResponse<List<SessionResponse>> listSessions(
            @AuthenticationPrincipal AgimateUserPrincipal principal) {
        UUID userId = UUID.fromString(principal.id());
        // One query for all of them: a person has a handful of devices, and asking per session would
        // be a query per row for data the listing shows either way.
        Map<UUID, List<PushSubscription>> push = pushSubscriptionService.byAuthSession(userId);

        List<SessionResponse> sessions = authSessionService.listActive(userId)
                .stream()
                .map(session -> SessionResponse.of(session, push.getOrDefault(session.getId(), List.of())))
                .toList();

        return SuccessResponse.ok(sessions);
    }

    @Operation(summary = "Sign out a device",
            description = "Revokes one session. Its refresh token stops working immediately; an "
                    + "access token it already holds lives out its remaining minutes.")
    @DeleteMapping("/{id}")
    public SuccessResponse<String> revokeSession(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Parameter(description = "Session id from the listing", required = true)
            @PathVariable("id") UUID id) {
        authSessionService.revokeOwn(UUID.fromString(principal.id()), id);
        return SuccessResponse.ok("success");
    }
}
