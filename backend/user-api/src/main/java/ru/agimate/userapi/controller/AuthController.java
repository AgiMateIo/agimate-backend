package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.security.UserPrincipal;
import ru.agimate.userapi.security.jwt.JwtUtils;
import ru.agimate.userapi.security.jwt.RefreshTokenService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    @Operation(
            summary = "Refresh authentication tokens",
            description = "Refreshes access and refresh tokens for the currently authenticated user"
    )
    @PostMapping("/refresh")
    public ResponseEntity<SuccessResponse<AuthResponse>> refreshToken(Authentication authentication) {
        // Extract user principal from authentication
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();

        // Generate new access token
        String newAccessToken = jwtUtils.generateToken(userPrincipal);

        // Create new refresh token (invalidate the old one if exists)
        String newRefreshToken = refreshTokenService.createRefreshToken(userPrincipal);

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();

        return ResponseEntity.ok(SuccessResponse.ok(authResponse));
    }
}