package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.controller.dto.OAuth2SuccessResponse;
import ru.agimate.userapi.database.entities.User;
import ru.agimate.userapi.security.UserPrincipal;
import ru.agimate.userapi.security.jwt.JwtUtils;
import ru.agimate.userapi.service.OAuthService;
import ru.agimate.userapi.security.jwt.RefreshTokenService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtils jwtUtils;
    private final OAuthService oAuthService;
    private final RefreshTokenService refreshTokenService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        try {

            if (response.isCommitted()) {
                logger.debug("Response has already been committed. ");
                return;
            }

            // Instead of redirecting, let's send the token as JSON response
            // This is more appropriate for API-based authentication
            sendTokenAsJsonResponse(response, authentication);
        } catch (Exception ex) {
            logger.error("Error in OAuth2SuccessHandler.onAuthenticationSuccess", ex);
            // Send error response as JSON instead of redirecting
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            String errorResponse = "{\"error\":\"Authentication processing failed\",\"message\":\"" +
                                  ex.getMessage().replace("\"", "'") + "\"}";
            response.getWriter().write(errorResponse);
            response.getWriter().flush();
        }
    }


    private void sendTokenAsJsonResponse(HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = getRegistrationId(authentication);

        User user = oAuthService.createOrGetUserFromOAuth(oAuth2User, registrationId);

        // Generate JWT tokens
        UserPrincipal userPrincipal = UserPrincipal.create(user);
        String accessToken = jwtUtils.generateToken(userPrincipal);

        // Generate refresh token
        String refreshToken = refreshTokenService.createRefreshToken(userPrincipal);

        // Prepare the OAuth2 success response object
        OAuth2SuccessResponse successResponse = OAuth2SuccessResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(86400L) // 24 hours in seconds
                .userId(user.getPubId().toString())
                .email(user.getEmail())
                .firstName(user.getFirstName() != null ? user.getFirstName() : "")
                .lastName(user.getLastName() != null ? user.getLastName() : "")
                .displayName(user.getDisplayName() != null ? user.getDisplayName() : user.getEmail())
                .build();

        // Prepare the response as JSON
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);

        // Use the same JsonUtils that's used elsewhere in the app
        response.getWriter().write(JsonUtils.writeValueAsString(successResponse));
        response.getWriter().flush();
    }

    private String getRegistrationId(Authentication authentication) {
        // Extract registrationId from the OAuth2 authentication details
        // First, try to get it from the OAuth2AuthenticationToken details
        if (authentication.getDetails() instanceof org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) {
            String registrationId = ((org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken) authentication.getDetails())
                    .getAuthorizedClientRegistrationId();
            if (registrationId != null) {
                return registrationId.toLowerCase();
            }
        }

        // For OIDC providers, we can also check the issuer
        if (authentication.getPrincipal() instanceof OidcUser) {
            String issuer = ((OidcUser) authentication.getPrincipal()).getIdToken().getIssuer().toString();
            if (issuer.contains("google")) {
                return "google";
            }
        }

        // Fallback to checking authorities
        for (var authority : authentication.getAuthorities()) {
            String auth = authority.getAuthority();
            if (auth.startsWith("OAUTH2_")) {
                return auth.substring(7).toLowerCase(); // OAUTH2_GOOGLE -> google
            }
        }

        // If we still can't determine, default to google
        return "google";
    }
}