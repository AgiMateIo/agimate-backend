package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.userapi.controller.dto.response.UserResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.mappers.UserMapper;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.auth.AuthSessionService;

import java.util.UUID;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Tag(name = "User", description = "User management endpoints")
public class UserController {

    private final UserService userService;
    private final AuthSessionService authSessionService;

    @Operation(summary = "Get user by id", description = "Returns user information by their id")
    @GetMapping("/{id}")
    @PreAuthorize("@userSecurityService.canAccessUser(authentication, #id)")
    public ResponseEntity<SuccessResponse<UserResponse>> getUserById(
            @Parameter(description = "ID of the user", required = true)
            @PathVariable("id") UUID id) {

        return userService.findById(id)
                .map(UserMapper::getUserResponse)
                .map(ur -> ResponseEntity.ok(SuccessResponse.ok(ur)))
                .orElse(ResponseEntity.notFound().build());

    }

    /**
     * The one request on which the session registry is consulted, and the reason it is this one: a
     * client asks who it is before it does anything else, so this is where a logout, a revoked
     * device or a password reset can catch up with an access token that was minted before them and
     * is signed for an hour yet. Everywhere else the signature is still the whole check —
     * see {@link AuthSessionService#isActive}.
     */
    @Operation(summary = "Get current user info",
            description = "Returns information about the currently authenticated user. Refuses a "
                    + "token whose sign-in has been revoked, which no other endpoint checks — a "
                    + "client that gets 401 here should refresh or sign in again.")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SuccessResponse<UserResponse>> getCurrentUser(Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            Object principal = authentication.getPrincipal();

            if (principal instanceof AgimateUserPrincipal agimateUserPrincipal) {
                if (!authSessionService.isActive(agimateUserPrincipal.authSessionId())) {
                    throw new UnauthorizedStatusException("This sign-in is no longer active");
                }

                // Find the user by their id to get the full user object
                return userService.findById(UUID.fromString(agimateUserPrincipal.id()))
                        .map(UserMapper::getUserResponse)
                        .map(ur -> ResponseEntity.ok(SuccessResponse.ok(ur)))
                        .orElse(ResponseEntity.status(403).build());
            }
        }

        return ResponseEntity.status(401).build();
    }


}
