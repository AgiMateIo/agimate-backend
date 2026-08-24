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
import org.springframework.web.util.UriComponentsBuilder;
import ru.agimate.common.rest.ErrorResponse;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.userapi.config.OAuthProperties;
import ru.agimate.userapi.database.entities.AuthClient;
import ru.agimate.userapi.database.entities.UserEntity;
import ru.agimate.userapi.security.jwt.RefreshTokenService;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapter;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapters;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserInfo;
import ru.agimate.userapi.service.auth.AuthSessionService;
import ru.agimate.userapi.service.auth.IssuedTokens;
import ru.agimate.userapi.service.auth.NativeAuthService;
import ru.agimate.userapi.service.auth.ProviderIdentityService;
import ru.agimate.userapi.service.auth.ProviderLinkProofService;

import java.io.IOException;
import java.util.Arrays;

/**
 * Where a round trip to a provider ends. Two things can come out of it and they are decided by one
 * cookie: a session, or a proof that a provider identity is available to be bound.
 *
 * <p>Who the person is has moved out of here entirely — {@link ProviderIdentityService} owns that
 * and owns the table it writes. What is left is transport: which flow this was, where the browser
 * or the application goes next, and what travels with it.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final RefreshTokenService refreshTokenService;
    private final OAuthProperties oAuthProperties;
    private final OAuthUserAdapters adapters;
    private final AuthSessionService authSessionService;
    private final NativeAuthService nativeAuthService;
    private final ProviderIdentityService providerIdentityService;
    private final ProviderLinkProofService linkProofService;


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
        OAuthUserAdapter adapter = adapters.require(registrationId);

        String linkMarker = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_LINK_COOKIE_NAME);
        if (CookieOAuth2AuthorizationRequestRepository.isLinkRequest(linkMarker)) {
            returnLinkProof(request, response, adapter, oAuth2User, registrationId);
            return;
        }

        String referralCode = getCookieValue(request,
                CookieOAuth2AuthorizationRequestRepository.OAUTH2_REF_COOKIE_NAME);

        UserEntity userEntity = providerIdentityService.resolve(
                adapter, adapter.extract(oAuth2User), referralCode);

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
     * The same round trip read as a binding rather than as a login — and stopped one step short of
     * being one.
     *
     * <p>Nothing is bound here, because nothing here knows whose account it would be bound to, and
     * that is deliberate. What comes back is a proof of the provider identity, spent afterwards by
     * {@code POST /auth/methods/link} carrying an access token: that request is what names the
     * account, and it names it with a header. A page on another origin can make a browser navigate,
     * which is how this round trip is started, but it cannot make it send that header — so an
     * account can no longer gain a way into it because its owner was persuaded to follow a link.
     *
     * <p>A ticket issued beforehand got this backwards: it named the account first, which handed the
     * decision to whoever finished the trip.
     *
     * <p>The address the provider reports is never consulted on this path, which is the point:
     * joining by address is what the sign-in does, and it cannot help somebody whose GitHub sits on
     * a different mailbox.
     */
    private void returnLinkProof(HttpServletRequest request, HttpServletResponse response,
                                 OAuthUserAdapter adapter, OAuth2User oAuth2User,
                                 String registrationId) throws IOException {
        OAuthUserInfo userInfo = adapter.extract(oAuth2User);
        String proof = linkProofService.issue(adapter.providerType(), userInfo);

        log.debug("{} round trip finished as a link proof", adapter.providerType());
        response.sendRedirect(withQueryParam(
                withQueryParam(linkTarget(request), "link_proof", proof), "provider", registrationId));
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
}
