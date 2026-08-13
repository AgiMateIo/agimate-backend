package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.common.security.jwt.JwtService;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapter;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapters;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;
import ru.agimate.userapi.service.UserService;

import java.io.IOException;
import java.util.Arrays;
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
    private final OAuthProperties oAuthProperties;
    private final OAuthUserAdapters adapters;


    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        try {
            if (response.isCommitted()) {
                logger.warn("Response has already been committed. ");
                return;
            }

            redirectToFrontend(request, response, authentication);
        } catch (OAuthLoginException ex) {
            // The provider did its part, so this is not an authentication failure — it is an account
            // we cannot open, and the person needs to read why.
            log.warn("OAuth login rejected: {}", ex.getMessage());
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
        } catch (Exception ex) {
            logger.error("Error in OAuth2SuccessHandler.onAuthenticationSuccess", ex);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication processing failed");
        }
    }


    private void redirectToFrontend(HttpServletRequest request, HttpServletResponse response,
                                    Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String registrationId = getRegistrationId(authentication);

        String referralCode = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_REF_COOKIE_NAME);

        UserEntity userEntity = createOrGetUserFromOAuth(oAuth2User, registrationId, referralCode);

        // Generate JWT tokens
        AgimateUserPrincipal agimateUserPrincipal = AgimateUserPrincipal.fromUser(
                userEntity.getId().toString(), userEntity.getRole());

        String refreshTokenId = UUID.randomUUID().toString();
        String refreshToken = jwtService.generateRefreshToken(agimateUserPrincipal, refreshTokenId);

        String redirectToUrl = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_REDIRECT_TO_COOKIE_NAME);
        OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRedirectUrl(redirectToUrl);

        refreshTokenService.setHttpOnlyRefreshTokenCookie(response, refreshToken,
                resolved.cookieDomain(), resolved.cookieSecure());
        response.sendRedirect(resolved.frontendRedirectUrl() + "#rti-" + refreshTokenId);
        response.getWriter().flush();
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(JsonUtils.writeValueAsString(new ErrorResponse(message)));
        response.getWriter().flush();
    }

    private String getCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(c -> name.equals(c.getName()))
                .findFirst()
                .map(Cookie::getValue)
                .orElse(null);
    }

    private String getRegistrationId(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            String registrationId = oauthToken.getAuthorizedClientRegistrationId();
            if (registrationId != null) {
                return registrationId.toLowerCase();
            }
        }
        throw new IllegalStateException("Unable to determine OAuth2 registration ID from authentication");
    }

    /**
     * @param referralCode the code the visitor arrived with, or null; it is honoured only when this
     *                     call ends up creating an account
     */
    public UserEntity createOrGetUserFromOAuth(OAuth2User oAuth2User, String registrationId,
                                               String referralCode) {
        OAuthUserAdapter adapter = adapters.require(registrationId);
        OAuthUserInfo userInfo = adapter.extract(oAuth2User);

        Optional<UserOAuthAccount> existingAccount = userOAuthAccountRepository
                .findByOauthProviderAndProviderUserIdWithUser(adapter.providerType(), userInfo.providerUserId());

        if (existingAccount.isPresent()) {
            return existingAccount.get().getUserEntity();
        }

        String email = requireEmail(userInfo, registrationId);
        String displayName = StringUtils.hasText(userInfo.displayName()) ? userInfo.displayName() : email;

        UserEntity userEntity = userService.findByEmail(email)
                .orElseGet(() -> userService.createUser(email, userInfo.firstName(), userInfo.lastName(),
                        displayName, resolveReferrer(referralCode)));

        UserOAuthAccount oAuthAccount = UserOAuthAccount.builder()
                .userEntity(userEntity)
                .firstName(userInfo.firstName())
                .lastName(userInfo.lastName())
                .email(email)
                .oauthProvider(adapter.providerType())
                .providerUserId(userInfo.providerUserId())
                .build();

        userOAuthAccountRepository.save(oAuthAccount);

        return userEntity;
    }

    /**
     * Resolved inside the creating branch alone: a link can only ever bring new people, so an
     * account that already exists keeps whoever brought it here in the first place. An unknown code
     * is a typo in a link or a campaign that outlived its owner — it is logged and dropped, never a
     * reason to turn a registration away.
     */
    private UUID resolveReferrer(String referralCode) {
        if (!CookieOAuth2AuthorizationRequestRepository.isValidRefCode(referralCode)) {
            return null;
        }
        return userService.findByReferralCode(referralCode)
                .map(UserEntity::getId)
                .orElseGet(() -> {
                    log.warn("Unknown referral code on signup: {}", referralCode);
                    return null;
                });
    }

    /**
     * The email is what ties a new sign-in to an account that already exists here, so an address the
     * provider does not vouch for would hand over somebody else's account to whoever claimed it.
     */
    private String requireEmail(OAuthUserInfo userInfo, String registrationId) {
        if (!StringUtils.hasText(userInfo.email())) {
            throw new OAuthLoginException(("No email address came from %s. Add one to your %s account, "
                    + "or sign in through another provider.").formatted(registrationId, registrationId));
        }
        if (!userInfo.emailVerified()) {
            throw new OAuthLoginException(("The email address of your %s account is not confirmed. "
                    + "Confirm it there, or sign in through another provider.").formatted(registrationId));
        }
        return userInfo.email();
    }
}
