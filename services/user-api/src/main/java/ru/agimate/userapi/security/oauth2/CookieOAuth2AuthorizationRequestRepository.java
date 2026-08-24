package ru.agimate.userapi.security.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.JsonUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Cookie-based implementation of AuthorizationRequestRepository for stateless OAuth2 flow.
 *
 * Stores OAuth2AuthorizationRequest in encrypted HTTP-only cookie instead of HTTP session.
 * Uses AES-256-GCM encryption to protect sensitive OAuth2 data (state, code_verifier, redirect_uri).
 * Uses compact DTO + JSON serialization to reduce cookie size from ~2-3KB to ~250-350 bytes.
 * This allows OAuth2 flow to work across multiple backend instances without session affinity.
 */
@Slf4j
public class CookieOAuth2AuthorizationRequestRepository implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    public static final String OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "oauth2_auth_request";
    public static final String OAUTH2_REDIRECT_TO_COOKIE_NAME = "oauth2_redirect_to";
    public static final String OAUTH2_REF_COOKIE_NAME = "oauth2_ref";
    public static final String OAUTH2_CODE_CHALLENGE_COOKIE_NAME = "oauth2_code_challenge";
    public static final String OAUTH2_LINK_COOKIE_NAME = "oauth2_link";
    public static final int COOKIE_EXPIRE_SECONDS = 900; // 15 minutes

    private static final Pattern REF_PATTERN = Pattern.compile("^[A-Za-z0-9]{1,16}$");

    /**
     * S256 and nothing else, which fixes the length at 43 base64url characters. The value is a hash
     * and travels in the clear like {@code redirect_to} next to it — what matters is that only the
     * application that started the login knows its preimage.
     */
    private static final Pattern CODE_CHALLENGE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** The only value the marker below is ever set to; anything else is not one of ours. */
    private static final String LINK_MARKER = "1";

    private final SecretKey encryptionKey;
    private final boolean cookieSecure;

    /**
     * @param encryptionKey shared across instances, so any of them can decrypt the cookie
     * @param cookieSecure  {@code app.oauth.cookie-secure} — off only for plain-HTTP local runs
     */
    public CookieOAuth2AuthorizationRequestRepository(SecretKey encryptionKey, boolean cookieSecure) {
        this.encryptionKey = encryptionKey;
        this.cookieSecure = cookieSecure;
        log.info("Using provided encryption key for OAuth2 cookie storage with compact DTO+JSON serialization");
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookie(request, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME)
                .map(this::deserialize)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest,
                                        HttpServletRequest request,
                                        HttpServletResponse response) {
        if (authorizationRequest == null) {
            deleteCookie(request, response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
            return;
        }

        String cookieValue = serialize(authorizationRequest);
        addCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, cookieValue, COOKIE_EXPIRE_SECONDS);

        String redirectTo = request.getParameter("redirect_to");
        if (redirectTo != null && !redirectTo.isBlank()) {
            addCookie(response, OAUTH2_REDIRECT_TO_COOKIE_NAME, redirectTo, COOKIE_EXPIRE_SECONDS);
        }

        String ref = request.getParameter("ref");
        if (isValidRefCode(ref)) {
            addCookie(response, OAUTH2_REF_COOKIE_NAME, ref, COOKIE_EXPIRE_SECONDS);
        }

        // Both of the following are cleared when this round trip does not ask for them, and that
        // matters more than setting them: they are deleted at the callback, so an abandoned trip —
        // the consent page closed, the back button — leaves one behind for a quarter of an hour, and
        // the next trip would be read as whatever the last one was. A stale link marker turns an
        // ordinary login into a binding that mints no session at all.

        // The PKCE challenge of the native client, which has to outlive the trip to the provider and
        // is unrelated to the code_verifier inside the authorization request — that one belongs to
        // our exchange with the provider, this one to the app's exchange with us.
        String codeChallenge = request.getParameter("code_challenge");
        if (isValidCodeChallenge(codeChallenge)) {
            addCookie(response, OAUTH2_CODE_CHALLENGE_COOKIE_NAME, codeChallenge, COOKIE_EXPIRE_SECONDS);
        } else {
            deleteCookie(request, response, OAUTH2_CODE_CHALLENGE_COOKIE_NAME);
        }

        // Says that this round trip was asked for as a binding rather than as a login, and says
        // nothing else — least of all whose binding. It used to carry a ticket naming an account,
        // which made the binding belong to whoever finished the trip; a trip is begun by following
        // a link, so it could be finished in a browser that was not the account holder's. The
        // callback now hands back a proof of the provider and the account is named afterwards, by a
        // request bearing an access token. Forging this marker turns your own login into a proof
        // only you can spend, which is worth nothing to anybody.
        if (LINK_MARKER.equals(request.getParameter("link"))) {
            addCookie(response, OAUTH2_LINK_COOKIE_NAME, LINK_MARKER, COOKIE_EXPIRE_SECONDS);
        } else {
            deleteCookie(request, response, OAUTH2_LINK_COOKIE_NAME);
        }
    }

    public static boolean isLinkRequest(String cookieValue) {
        return LINK_MARKER.equals(cookieValue);
    }

    public static boolean isValidCodeChallenge(String value) {
        return value != null && CODE_CHALLENGE_PATTERN.matcher(value).matches();
    }

    /**
     * The shape of a referral code, checked on both sides of the round trip. Unlike
     * {@code redirect_to}, nothing validates it further down, and the cookie it travels in belongs to
     * the client — so writing it into a response header and reading it back off a request are two
     * separate places that both need this, not one.
     */
    public static boolean isValidRefCode(String value) {
        return value != null && REF_PATTERN.matcher(value).matches();
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        if (authorizationRequest != null) {
            deleteCookie(request, response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        }
        deleteCookie(request, response, OAUTH2_REDIRECT_TO_COOKIE_NAME);
        deleteCookie(request, response, OAUTH2_REF_COOKIE_NAME);
        deleteCookie(request, response, OAUTH2_CODE_CHALLENGE_COOKIE_NAME);
        deleteCookie(request, response, OAUTH2_LINK_COOKIE_NAME);
        return authorizationRequest;
    }

    private String serialize(OAuth2AuthorizationRequest authorizationRequest) {
        try {
            OAuth2AuthorizationRequestDTO dto = OAuth2AuthorizationRequestDTO.fromAuthorizationRequest(authorizationRequest);

            String json = JsonUtils.writeValueAsString(dto);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

            log.debug("Serialized OAuth2AuthorizationRequest to {} bytes (JSON)", jsonBytes.length);

            return CryptoUtils.encryptToBase64(jsonBytes, encryptionKey);
        } catch (Exception e) {
            log.error("Failed to serialize and encrypt OAuth2AuthorizationRequest", e);
            throw new IllegalStateException("Failed to serialize OAuth2AuthorizationRequest", e);
        }
    }

    /**
     * Returns {@code null} for anything unreadable — tampered, truncated, or encrypted under a
     * retired key. Spring Security then sees no pending authorization request and restarts the
     * flow, which is the behaviour we want: a broken cookie is a stale login, not a server error.
     */
    private OAuth2AuthorizationRequest deserialize(Cookie cookie) {
        try {
            byte[] decrypted = CryptoUtils.decryptFromBase64(cookie.getValue(), encryptionKey);

            String json = new String(decrypted, StandardCharsets.UTF_8);
            OAuth2AuthorizationRequestDTO dto = JsonUtils.readValue(json, OAuth2AuthorizationRequestDTO.class);

            log.debug("Deserialized OAuth2AuthorizationRequest from {} bytes (JSON)", decrypted.length);

            return dto.toAuthorizationRequest();
        } catch (Exception e) {
            log.error("Failed to decrypt and deserialize OAuth2AuthorizationRequest from cookie", e);
            return null;
        }
    }

    private java.util.Optional<Cookie> getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName())) {
                    return java.util.Optional.of(cookie);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private void addCookie(HttpServletResponse response, String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAge);
        cookie.setSecure(cookieSecure);
        response.addCookie(cookie);
    }

    private void deleteCookie(HttpServletRequest request, HttpServletResponse response, String name) {
        Cookie deletionCookie = new Cookie(name, "");
        deletionCookie.setPath("/");
        deletionCookie.setMaxAge(0);
        deletionCookie.setSecure(cookieSecure); // must match the attributes it is replacing
        response.addCookie(deletionCookie);
    }
}
