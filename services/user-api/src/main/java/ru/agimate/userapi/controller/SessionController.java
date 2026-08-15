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
import ru.agimate.userapi.service.auth.AuthSessionService;

import java.util.List;
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

    @Operation(summary = "List my active sessions",
            description = "One entry per signed-in device, most recently seen first")
    @GetMapping("/")
    public SuccessResponse<List<SessionResponse>> listSessions(
            @AuthenticationPrincipal AgimateUserPrincipal principal) {
        List<SessionResponse> sessions = authSessionService.listActive(UUID.fromString(principal.id()))
                .stream()
                .map(SessionResponse::of)
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
