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
import org.springframework.web.util.UriComponentsBuilder;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.database.entities.UserOAuthAccount;
import ru.agimate.userapi.database.repositories.UserOAuthAccountRepository;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapter;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapters;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;
import ru.agimate.userapi.service.UserService;
import ru.agimate.userapi.service.auth.AuthSessionService;
import ru.agimate.userapi.service.auth.IssuedTokens;
import ru.agimate.userapi.service.auth.LoginMethodService;
import ru.agimate.userapi.service.auth.NativeAuthService;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final RefreshTokenService refreshTokenService;
    private final UserOAuthAccountRepository userOAuthAccountRepository;
    private final UserService userService;
    private final OAuthProperties oAuthProperties;
    private final OAuthUserAdapters adapters;
    private final AuthSessionService authSessionService;
    private final NativeAuthService nativeAuthService;
    private final LoginMethodService loginMethodService;


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

        String linkTicket = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_LINK_TICKET_COOKIE_NAME);
        if (CookieOAuth2AuthorizationRequestRepository.isValidLinkTicket(linkTicket)) {
            linkProvider(request, response, oAuth2User, registrationId, linkTicket);
            return;
        }

        String referralCode = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_REF_COOKIE_NAME);

        UserEntity userEntity = createOrGetUserFromOAuth(oAuth2User, registrationId, referralCode);

        String redirectToUrl = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_REDIRECT_TO_COOKIE_NAME);

        if (oAuthProperties.isNativeRedirect(redirectToUrl)) {
            redirectToNativeClient(request, response, userEntity, redirectToUrl);
        } else {
            redirectToBrowser(request, response, userEntity, redirectToUrl);
        }
        response.getWriter().flush();
    }

    /**
     * The same round trip read as a binding rather than as a login: whoever started it was already
     * signed in, and what comes back is another way into the account they were signed into.
     *
     * <p>The address the provider reports is not consulted at all here — that is the whole point.
     * Joining by address is what the login does, and it cannot help somebody whose GitHub sits on a
     * different mailbox, which until now silently gave them a second account.
     *
     * <p>Nothing is minted: the person already has a session. The outcome travels back as a query
     * parameter, because the only thing that can act on it is the page they came from.
     */
    private void linkProvider(HttpServletRequest request, HttpServletResponse response,
                              OAuth2User oAuth2User, String registrationId, String linkTicket)
            throws IOException {
        OAuthUserAdapter adapter = adapters.require(registrationId);
        OAuthUserInfo userInfo = adapter.extract(oAuth2User);
        String target = linkTarget(request);

        try {
            LoginMethodService.LinkOutcome outcome = loginMethodService.link(linkTicket,
                    adapter.providerType(), userInfo.providerUserId(), userInfo.email(),
                    userInfo.firstName(), userInfo.lastName());

            response.sendRedirect(switch (outcome) {
                case TAKEN -> withQueryParam(target, "link_error", "already_linked");
                case PROVIDER_OCCUPIED -> withQueryParam(target, "link_error", "provider_already_linked");
                case LINKED, ALREADY_YOURS -> withQueryParam(target, "linked", registrationId);
            });
        } catch (BaseHttpStatusException ex) {
            // A ticket that expired, was spent, or belongs to an account that is gone. The person is
            // looking at a page, not at a status code, so it comes back as one.
            log.warn("provider linking refused: {}", ex.getMessage());
            response.sendRedirect(withQueryParam(target, "link_error", "invalid_ticket"));
        }
    }

    /** Where a linking round trip lands: the address it started from when that one is allowed. */
    private String linkTarget(HttpServletRequest request) {
        String redirectToUrl = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_REDIRECT_TO_COOKIE_NAME);

        return oAuthProperties.isNativeRedirect(redirectToUrl)
                ? redirectToUrl
                : oAuthProperties.resolveFromRedirectUrl(redirectToUrl, request).frontendRedirectUrl();
    }

    /**
     * The browser flow, unchanged: the token itself stays in an httpOnly cookie and only its id
     * travels in the fragment, where it is allowed to be seen.
     */
    private void redirectToBrowser(HttpServletRequest request, HttpServletResponse response,
                                   UserEntity userEntity, String redirectToUrl) throws IOException {
        IssuedTokens tokens = authSessionService.open(
                userEntity, AuthClient.WEB, request.getHeader("User-Agent"));

        OAuthProperties.ResolvedDomain resolved = oAuthProperties.resolveFromRedirectUrl(redirectToUrl, request);
        refreshTokenService.setHttpOnlyRefreshTokenCookie(response, tokens.refreshToken(),
                resolved.cookieDomain(), resolved.cookieSecure());
        response.sendRedirect(resolved.frontendRedirectUrl() + "#rti-" + tokens.refreshTokenId());
    }

    /**
     * The native flow ends with a one-time code and no cookie whatsoever: a {@code Set-Cookie} for
     * the web domain in answer to an app is at best ignored and at worst mistaken for a mechanism.
     *
     * <p>A missing challenge is reported through the redirect rather than as a page, because the
     * only thing looking at this response is the application.
     */
    private void redirectToNativeClient(HttpServletRequest request, HttpServletResponse response,
                                        UserEntity userEntity, String redirectToUrl) throws IOException {
        String codeChallenge = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_CODE_CHALLENGE_COOKIE_NAME);

        if (!CookieOAuth2AuthorizationRequestRepository.isValidCodeChallenge(codeChallenge)) {
            log.warn("native login for {} started without a usable code_challenge", redirectToUrl);
            response.sendRedirect(withQueryParam(redirectToUrl, "error", "invalid_request"));
            return;
        }

        String code = nativeAuthService.issueCode(userEntity.getId(), codeChallenge, redirectToUrl);
        response.sendRedirect(withQueryParam(redirectToUrl, "code", code));
    }

    private static String withQueryParam(String url, String name, String value) {
        return UriComponentsBuilder.fromUriString(url)
                .queryParam(name, value)
                .build()
                .toUriString();
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
