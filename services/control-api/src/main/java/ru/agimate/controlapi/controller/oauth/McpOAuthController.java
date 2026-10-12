package ru.agimate.controlapi.controller.oauth;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UrlPathHelper;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthEndpoints;
import ru.agimate.controlapi.service.mcp.oauth.McpOAuthTokens;
import ru.agimate.controlapi.service.mcp.oauth.McpTokenService;
import ru.agimate.controlapi.service.mcp.oauth.OAuthErrors;
import ru.agimate.controlapi.service.mcp.oauth.PkceS256;
import ru.agimate.controlapi.service.mcp.oauth.TokenResult;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The public, anonymous half of the MCP server's authorization server: metadata and the token and
 * revocation endpoints (docs/decisions/mcp-server-oauth.md). Bodies are OAuth's own — not wrapped in
 * {@code SuccessResponse}, errors as {@code {error, error_description}} — because the reader is an
 * OAuth client, not our front.
 *
 * <p>Metadata answers both the bare well-known address and the one with the issuer's or resource's
 * path appended: a client derives the latter from a path-carrying issuer (RFC 8414) or resource
 * (RFC 9728), and the ingress forwards it here with the suffix kept. A suffix naming anything else is
 * not ours — 404.
 */
@RestController
@RequiredArgsConstructor
public class McpOAuthController {

    private static final CacheControl METADATA_CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePublic();
    private static final UrlPathHelper URL_PATH_HELPER = new UrlPathHelper();

    private final McpOAuthEndpoints endpoints;
    private final McpTokenService tokenService;
    private final InboundRateLimiter rateLimiter;

    @GetMapping({McpOAuthEndpoints.PROTECTED_RESOURCE_METADATA_PATH, McpOAuthEndpoints.PROTECTED_RESOURCE_METADATA_PATH + "/**"})
    public ResponseEntity<Map<String, Object>> protectedResourceMetadata(HttpServletRequest request) {
        if (!suffixIs(request, McpOAuthEndpoints.PROTECTED_RESOURCE_METADATA_PATH, endpoints.resourcePath())) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resource", endpoints.resource());
        body.put("authorization_servers", List.of(endpoints.issuer()));
        body.put("bearer_methods_supported", List.of("header"));
        body.put("resource_name", "AgiMate");
        return ResponseEntity.ok().cacheControl(METADATA_CACHE).body(body);
    }

    @GetMapping({McpOAuthEndpoints.AUTHORIZATION_SERVER_METADATA_PATH, McpOAuthEndpoints.AUTHORIZATION_SERVER_METADATA_PATH + "/**"})
    public ResponseEntity<Map<String, Object>> authorizationServerMetadata(HttpServletRequest request) {
        if (!suffixIs(request, McpOAuthEndpoints.AUTHORIZATION_SERVER_METADATA_PATH, endpoints.issuerPath())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().cacheControl(METADATA_CACHE).body(authorizationServerMetadata());
    }

    /**
     * OIDC discovery, which the spec has clients try after RFC 8414. Formally OIDC wants
     * {@code jwks_uri} and the {@code id_token} fields; we issue no ID tokens, and MCP clients read
     * only the OAuth fields.
     */
    @GetMapping(McpOAuthEndpoints.OPENID_CONFIGURATION_PATH)
    public ResponseEntity<Map<String, Object>> openidConfiguration() {
        return ResponseEntity.ok().cacheControl(METADATA_CACHE).body(authorizationServerMetadata());
    }

    @PostMapping(value = McpOAuthEndpoints.TOKEN_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> token(@RequestParam MultiValueMap<String, String> form) {
        // RFC 6749, 3.2: a parameter sent twice makes the request invalid, not «take the first».
        if (isRepeated(form)) {
            return error(HttpStatus.BAD_REQUEST, OAuthErrors.INVALID_REQUEST, "A parameter is repeated");
        }
        String grantType = form.getFirst("grant_type");
        boolean byCode = "authorization_code".equals(grantType);
        if (!byCode && !"refresh_token".equals(grantType)) {
            return error(HttpStatus.BAD_REQUEST, OAuthErrors.UNSUPPORTED_GRANT_TYPE,
                    "Supported grant types: authorization_code, refresh_token");
        }
        String credential = form.getFirst(byCode ? "code" : "refresh_token");
        if (!withinLimit(credential)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, OAuthErrors.TEMPORARILY_UNAVAILABLE, "Too many token requests");
        }
        String clientId = form.getFirst("client_id");
        TokenResult result = byCode
                ? tokenService.exchangeCode(credential, form.getFirst("code_verifier"), clientId,
                        form.getFirst("redirect_uri"), form.getFirst("resource"))
                : tokenService.refresh(credential, clientId);
        return switch (result) {
            case TokenResult.Issued issued -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("access_token", issued.accessToken());
                body.put("token_type", "Bearer");
                body.put("expires_in", issued.expiresIn());
                body.put("refresh_token", issued.refreshToken());
                yield ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body);
            }
            case TokenResult.Refused refused -> error(HttpStatus.BAD_REQUEST, refused.error(), refused.description());
        };
    }

    /** RFC 7009: the answer is 200 whether the token was known or not; errors are RFC 6749's. */
    @PostMapping(value = McpOAuthEndpoints.REVOKE_PATH, consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Map<String, Object>> revoke(@RequestParam MultiValueMap<String, String> form) {
        if (isRepeated(form)) {
            return error(HttpStatus.BAD_REQUEST, OAuthErrors.INVALID_REQUEST, "A parameter is repeated");
        }
        String token = form.getFirst("token");
        if (!withinLimit(token)) {
            return error(HttpStatus.TOO_MANY_REQUESTS, OAuthErrors.TEMPORARILY_UNAVAILABLE, "Too many revocation requests");
        }
        if (token != null && !token.isBlank()) {
            tokenService.revoke(token, form.getFirst("client_id"));
        }
        return ResponseEntity.ok().build();
    }

    private Map<String, Object> authorizationServerMetadata() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", endpoints.issuer());
        body.put("authorization_endpoint", endpoints.authorizationEndpoint());
        body.put("token_endpoint", endpoints.tokenEndpoint());
        body.put("revocation_endpoint", endpoints.revocationEndpoint());
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        body.put("code_challenge_methods_supported", List.of(PkceS256.METHOD));
        body.put("token_endpoint_auth_methods_supported", List.of("none"));
        body.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        body.put("client_id_metadata_document_supported", true);
        body.put("authorization_response_iss_parameter_supported", true);
        return body;
    }

    /** The part of the path after the well-known prefix must be empty or exactly {@code expected}. */
    private static boolean suffixIs(HttpServletRequest request, String prefix, String expected) {
        // Decoded and without the context path — the same form the mapping itself was matched against.
        String path = URL_PATH_HELPER.getPathWithinApplication(request);
        String suffix = path.length() > prefix.length() ? path.substring(prefix.length()) : "";
        return suffix.isEmpty() || suffix.equals(expected);
    }

    private static boolean isRepeated(MultiValueMap<String, String> form) {
        return form.values().stream().anyMatch(values -> values.size() > 1);
    }

    /**
     * Keyed by the presented code or token: the only thing in an anonymous request that one caller
     * cannot share with everybody else. A credential that is not even well-formed is refused without a
     * lookup, so it needs no bucket — and a bucket per made-up value would protect nothing.
     */
    private boolean withinLimit(String credential) {
        return McpOAuthTokens.keyIdOf(credential)
                .map(keyId -> rateLimiter.tryAcquire(InboundRateLimiter.Scope.OAUTH_TOKEN,
                        UUID.nameUUIDFromBytes(keyId.getBytes(StandardCharsets.UTF_8))))
                .orElse(true);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String error, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(body);
    }
}
