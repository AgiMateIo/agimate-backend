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
    public static final int COOKIE_EXPIRE_SECONDS = 900; // 15 minutes

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
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
                                                                 HttpServletResponse response) {
        OAuth2AuthorizationRequest authorizationRequest = loadAuthorizationRequest(request);
        if (authorizationRequest != null) {
            deleteCookie(request, response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        }
        deleteCookie(request, response, OAUTH2_REDIRECT_TO_COOKIE_NAME);
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
