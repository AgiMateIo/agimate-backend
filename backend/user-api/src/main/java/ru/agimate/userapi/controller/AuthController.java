package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.security.CustomUserDetailsService;
import ru.agimate.userapi.security.UserPrincipal;
import ru.agimate.userapi.security.jwt.JwtUtils;
import ru.agimate.userapi.security.jwt.RefreshTokenService;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication endpoints")
public class AuthController {

    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    @Operation(summary = "Refresh authentication tokens", description = "Returns new access and refresh tokens for authenticated users")
    @GetMapping("/refresh")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SuccessResponse<AuthResponse>> refreshToken(Authentication authentication) {
        // Extract user details from the current authentication
        UserPrincipal userPrincipal = (UserPrincipal) authentication.getPrincipal();

        // Generate new access token
        String newAccessToken = jwtUtils.generateToken(userPrincipal);

        // Create new refresh token
        String refreshToken = refreshTokenService.createRefreshToken(userPrincipal);

        AuthResponse authResponse = AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken)
                .build();

        return ResponseEntity.ok(SuccessResponse.ok(authResponse));
    }
}