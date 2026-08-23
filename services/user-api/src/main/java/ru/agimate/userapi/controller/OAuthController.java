package ru.agimate.userapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.UnauthorizedStatusException;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.common.security.jwt.WrappedJwt;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.controller.dto.request.auth.LogoutRequest;
import ru.agimate.userapi.controller.dto.request.auth.NativeTokenRequest;
import ru.agimate.userapi.controller.dto.request.auth.RefreshRequest;
import ru.agimate.userapi.controller.dto.response.auth.AuthResponse;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.mappers.AuthMapper;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.service.auth.AuthSessionService;
import ru.agimate.userapi.service.auth.IssuedTokens;
import ru.agimate.userapi.service.auth.NativeAuthService;

/**
 * Where a session is renewed or ended. Both operations take the refresh token from the cookie first
 * and from the request body second — the browser keeps working exactly as before, and an installed
 * application, which has no cookie jar the login could have written to, says so explicitly.
 */
@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
@Slf4j
public class OAuthController {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuthSessionService authSessionService;
    private final NativeAuthService nativeAuthService;
    private final OAuthProperties oAuthProperties;

    @Operation(
            summary = "Refresh authentication tokens",
            description = "Rotates the session. Web callers send the refresh token identifier and "
                    + "their cookie; native callers send the refresh token in the body and receive "
                    + "the new one the same way.",
            responses = @ApiResponse(
                    description = "New token pair",
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
        String cookieToken = refreshTokenService.getRefreshTokenFromCookie(request);

        if (StringUtils.hasText(cookieToken)) {
            OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRequest(request);
            String jti = webJti(cookieToken, refreshRequest.refreshTokenId(), response, resolved);

            IssuedTokens tokens = authSessionService.refresh(jti, AuthClient.WEB);
            refreshTokenService.setHttpOnlyRefreshTokenCookie(response, tokens.refreshToken(),
                    resolved.cookieDomain(), resolved.cookieSecure());

            return ResponseEntity.ok(SuccessResponse.ok(AuthMapper.forWeb(tokens)));
        }

        IssuedTokens tokens = authSessionService.refresh(
                nativeJti(refreshRequest.refreshToken()), AuthClient.NATIVE);
        return ResponseEntity.ok(SuccessResponse.ok(AuthMapper.forNative(tokens)));
    }

    @Operation(summary = "Logout", description = "Revokes the session the refresh token belongs to")
    @PostMapping("/logout")
    public ResponseEntity<SuccessResponse<String>> logout(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody
            LogoutRequest logoutRequest
    ) {
        String cookieToken = refreshTokenService.getRefreshTokenFromCookie(request);

        if (StringUtils.hasText(cookieToken)) {
            OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRequest(request);
            String jti = webJti(cookieToken, logoutRequest.refreshTokenId(), response, resolved);

            // Revoking the registry entry is what ends the session — clearing the cookie only tidies
            // up the honest client's browser, not a copy of the value.
            authSessionService.closeByJti(jti, AuthClient.WEB);
            refreshTokenService.deleteRefreshTokenCookie(response, resolved.cookieDomain());
        } else {
            authSessionService.closeByJti(nativeJti(logoutRequest.refreshToken()), AuthClient.NATIVE);
        }

        return ResponseEntity.ok(SuccessResponse.ok("success"));
    }

    @Operation(
            summary = "Exchange a native one-time code for tokens",
            description = "The last step of a login that came back to an App Link, a Universal Link "
                    + "or a custom scheme. The code is worth nothing without the PKCE verifier of "
                    + "the application that started the login.",
            responses = @ApiResponse(
                    description = "Token pair, refresh token included",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = AuthResponse.class)
                    )
            )
    )
    @PostMapping("/native/token")
    public ResponseEntity<SuccessResponse<AuthResponse>> exchangeNativeCode(
            HttpServletRequest request,
            @Valid @RequestBody
            NativeTokenRequest tokenRequest
    ) {
        String deviceLabel = StringUtils.hasText(tokenRequest.deviceName())
                ? tokenRequest.deviceName()
                : request.getHeader("User-Agent");

        IssuedTokens tokens = nativeAuthService.exchange(
                tokenRequest.code(), tokenRequest.codeVerifier(), tokenRequest.redirectUri(), deviceLabel);

        return ResponseEntity.ok(SuccessResponse.ok(AuthMapper.forNative(tokens)));
    }

    /**
     * The id is taken from the signed claims rather than from the body: the caller does not get to
     * choose which session is looked up, and the body value is only ever a claim to match against.
     */
    private String webJti(String cookieToken, String refreshTokenId, HttpServletResponse response,
                          OAuthProperties.ResolvedDomain resolved) {
        if (!StringUtils.hasText(refreshTokenId)) {
            throw new BadRequestStatusException("refreshTokenId is required alongside the cookie");
        }
        WrappedJwt wrapped = jwtService.extractClaimsFromValidRefreshToken(cookieToken, refreshTokenId)
                .orElseThrow(() -> {
                    refreshTokenService.deleteRefreshTokenCookie(response, resolved.cookieDomain());
                    return new ForbiddenStatusException("Invalid or expired refresh token");
                });
        return wrapped.claims().getId();
    }

    private String nativeJti(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            throw new UnauthorizedStatusException("Refresh token not found");
        }
        return jwtService.extractClaimsFromValidRefreshToken(refreshToken)
                .orElseThrow(() -> new ForbiddenStatusException("Invalid or expired refresh token"))
                .claims()
                .getId();
    }

}
