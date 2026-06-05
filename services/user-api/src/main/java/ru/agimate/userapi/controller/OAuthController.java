package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.controller.dto.request.auth.LogoutRequest;
import ru.agimate.userapi.controller.dto.request.auth.RefreshRequest;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.service.UserService;

import java.util.UUID;

@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
@Slf4j
public class OAuthController {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserService userService;
    private final OAuthProperties oAuthProperties;

    @Operation(
            summary = "Refresh authentication tokens",
            description = "Takes a valid refresh token from cookie and returns new access token",
            responses = @ApiResponse(
                    description = "New access token",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            )
    )
    @PostMapping("/refresh")
    public ResponseEntity<SuccessResponse<AuthResponse>> refreshToken(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody
            RefreshRequest refreshRequest
    ) {
        OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRequest(request);

        String refreshTokenValue = requireRefreshTokenFromCookie(request);

        if (refreshTokenService.isAlreadyUsed(refreshRequest.refreshTokenId())) {
            throw new ForbiddenStatusException("Refresh token already used");
        }

        var wrappedJwtOptional = jwtService.extractClaimsFromValidRefreshToken(refreshTokenValue, refreshRequest.refreshTokenId());
        if (wrappedJwtOptional.isEmpty()) {
            refreshTokenService.deleteRefreshTokenCookie(response, resolved.cookieDomain());
            throw new ForbiddenStatusException("Invalid or expired refresh token");
        }


        var subject = wrappedJwtOptional.get().claims().getSubject();
        UserEntity userEntity = userService.findById(UUID.fromString(subject))
                .orElseThrow(() -> new BadRequestStatusException("User doesn't exist"));

        var agimateUserPrincipal = AgimateUserPrincipal.fromUser(
                userEntity.getId().toString(), userEntity.getRole());
        String newAccessToken = jwtService.generateAccessToken(agimateUserPrincipal);
        String newRefreshTokenId = UUID.randomUUID().toString();
        String newRefreshToken = jwtService.generateRefreshToken(agimateUserPrincipal, newRefreshTokenId);

        refreshTokenService.markTokenAsUsed(refreshTokenValue);

        refreshTokenService.setHttpOnlyRefreshTokenCookie(response, newRefreshToken,
                resolved.cookieDomain(), resolved.cookieSecure());

        AuthResponse authResponse = new AuthResponse(newAccessToken, newRefreshTokenId);

        return ResponseEntity.ok(SuccessResponse.ok(authResponse));
    }

    @Operation(summary = "Logout")
    @PostMapping("/logout")
    public ResponseEntity<SuccessResponse<String>> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody
            LogoutRequest logoutRequest
    ) {
        OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRequest(request);

        String refreshTokenValue = requireRefreshTokenFromCookie(request);

        var wrappedJwtOptional = jwtService.extractClaimsFromValidRefreshToken(refreshTokenValue, logoutRequest.refreshTokenId());
        if (wrappedJwtOptional.isEmpty()) {
            refreshTokenService.deleteRefreshTokenCookie(response, resolved.cookieDomain());
            throw new ForbiddenStatusException("Invalid or expired refresh token");
        }

        refreshTokenService.markTokenAsUsed(refreshTokenValue);
        refreshTokenService.deleteRefreshTokenCookie(response, resolved.cookieDomain());

        return ResponseEntity.ok(SuccessResponse.ok("success"));
    }

    private String requireRefreshTokenFromCookie(HttpServletRequest request) {
        String refreshTokenValue = refreshTokenService.getRefreshTokenFromCookie(request);
        if (refreshTokenValue == null || refreshTokenValue.isEmpty()) {
            throw new UnauthorizedStatusException("Refresh token not found");
        }
        return refreshTokenValue;
    }

}
