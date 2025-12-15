package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.userapi.controller.dto.request.auth.RefreshTokenRequest;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.security.CustomUserDetailsService;
import ru.agimate.userapi.security.UserPrincipal;
import ru.agimate.userapi.security.jwt.JwtUtils;
import ru.agimate.userapi.security.jwt.RefreshTokenService;

@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
@Slf4j
public class OAuthController {

    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;
    private final CustomUserDetailsService userDetailsService;

    @Operation(
            summary = "Refresh authentication tokens",
            description = "Takes a valid refresh token and returns new access and refresh tokens",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "Refresh token required to get new access and refresh tokens",
                    required = true,
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = "application/json",
                            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = RefreshTokenRequest.class)
                    )
            )
    )
    @PostMapping("/refresh")
    public ResponseEntity<SuccessResponse<AuthResponse>> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        // Verify the refresh token
        var refreshToken = refreshTokenService.verifyRefreshToken(request.refreshToken());

        if (refreshToken == null) {
            throw new BadRequestStatusException("Invalid or expired refresh token");
        }

        // Find the user associated with this refresh token
        UserPrincipal userPrincipal = (UserPrincipal) userDetailsService.loadUserById(refreshToken.getUserId());

        // Generate new access token
        String newAccessToken = jwtUtils.generateToken(userPrincipal);

        // Create new refresh token (invalidate the old one)
        refreshTokenService.deleteRefreshToken(request.refreshToken());
        String newRefreshToken = refreshTokenService.createRefreshToken(userPrincipal);

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();

        return ResponseEntity.ok(SuccessResponse.ok(authResponse));
    }

    @GetMapping("/error")
    public ResponseEntity<String> handleOAuthError(@RequestParam(required = false) String error) {
        log.error("OAuth2 authentication error: {}", error);
        return ResponseEntity.badRequest().body("OAuth2 authentication failed: " + error);
    }
}