package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.database.entities.OAuthProviderType;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.service.UserService;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final UserOAuthAccountRepository userOAuthAccountRepository;
    private final UserService userService;


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            if (response.isCommitted()) {
                logger.warn("Response has already been committed. ");
                return;
            }

            redirectToFrontend(response, authentication);
        } catch (Exception ex) {
            logger.error("Error in OAuth2SuccessHandler.onAuthenticationSuccess", ex);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write(JsonUtils.writeValueAsString(new ErrorResponse("Authentication processing failed")));
            response.getWriter().flush();
        }
    }


    private void redirectToFrontend(HttpServletResponse response, Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = getRegistrationId(authentication);

        UserEntity userEntity = createOrGetUserFromOAuth(oAuth2User, registrationId);

        // Generate JWT tokens
        AgimateUserPrincipal agimateUserPrincipal = new AgimateUserPrincipal(userEntity.getPubId().toString());

        String refreshTokenId = UUID.randomUUID().toString();
        String refreshToken = jwtService.generateRefreshToken(agimateUserPrincipal, refreshTokenId);
        log.info("created a new JWT token: {}", refreshTokenId);
        log.info("created a new JWT token: {}", refreshToken);

        refreshTokenService.setHttpOnlyRefreshTokenCookie(response, refreshToken);
        response.sendRedirect("http://www.agimate.lc:8000/login#" + refreshTokenId);
        response.getWriter().flush();
    }

    private String getRegistrationId(Authentication authentication) {
        // Extract registrationId from the OAuth2 authentication details
        // First, try to get it from the OAuth2AuthenticationToken details
        if (authentication.getDetails() instanceof OAuth2AuthenticationToken) {
            String registrationId = ((OAuth2AuthenticationToken) authentication.getDetails())
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

    public UserEntity createOrGetUserFromOAuth(OAuth2User oAuth2User, String registrationId) {
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String providerUserId = attributes.get("sub").toString(); // Google uses "sub", GitHub uses "id", etc.

        OAuthProviderType providerType = OAuthProviderType.fromString(registrationId);

        Optional<UserOAuthAccount> existingAccount = userOAuthAccountRepository
                .findByOauthProviderAndProviderUserIdWithUser(providerType, providerUserId);

        if (existingAccount.isPresent()) {
            return existingAccount.get().getUserEntity();
        }

        String email = attributes.get("email").toString();
        String firstName = attributes.get("given_name") != null ? attributes.get("given_name").toString() : null;
        String lastName = attributes.get("family_name") != null ? attributes.get("family_name").toString() : null;
        String displayName = attributes.get("name") != null ? attributes.get("name").toString() : email;

        UserEntity userEntity = userService.findByEmail(email)
                .orElseGet(() -> userService.createUser(email, firstName, lastName, displayName));

        UserOAuthAccount oAuthAccount = UserOAuthAccount.builder()
                .userEntity(userEntity)
                .firstName(firstName)
                .lastName(lastName)
                .email(email)
                .oauthProvider(providerType)
                .providerUserId(providerUserId)
                .build();

        userOAuthAccountRepository.save(oAuthAccount);

        return userEntity;
    }
}