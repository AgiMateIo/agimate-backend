package ru.agimate.userapi.security.oauth2;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

import java.util.Map;

/**
 * Compact Data Transfer Object for OAuth2AuthorizationRequest cookie storage.
 *
 * Stores only critical fields that cannot be reconstructed from Spring Security configuration:
 * - state: CSRF protection token (random, must be preserved)
 * - redirectUri: Dynamic baseUrl-dependent redirect URI (must be preserved)
 * - clientRegistrationId: OAuth2 provider identifier (yandex, google, etc.)
 * - codeVerifier: PKCE code verifier (optional, for enhanced security)
 * - authorizationRequestUri: Full authorization URI (for debugging/retry)
 *
 * All other fields (scopes, authorizationUri, tokenUri, etc.) are reconstructed from
 * Spring Security ClientRegistration configuration.
 *
 * Reduces serialized size from ~2-3KB to ~150-250 bytes (8-10x reduction).
 */
@Value
public class OAuth2AuthorizationRequestDTO {

    /**
     * CSRF protection state parameter (required)
     */
    String state;

    /**
     * OAuth2 redirect URI with dynamic baseUrl (required)
     */
    String redirectUri;

    /**
     * Client registration ID: yandex, google, etc. (required)
     */
    String clientRegistrationId;

    /**
     * PKCE code verifier for enhanced security (optional)
     */
    String codeVerifier;

    /**
     * Full authorization request URI (optional, for debugging)
     */
    String authorizationRequestUri;

    @JsonCreator
    public OAuth2AuthorizationRequestDTO(
            @JsonProperty("state") String state,
            @JsonProperty("redirectUri") String redirectUri,
            @JsonProperty("clientRegistrationId") String clientRegistrationId,
            @JsonProperty("codeVerifier") String codeVerifier,
            @JsonProperty("authorizationRequestUri") String authorizationRequestUri) {
        this.state = state;
        this.redirectUri = redirectUri;
        this.clientRegistrationId = clientRegistrationId;
        this.codeVerifier = codeVerifier;
        this.authorizationRequestUri = authorizationRequestUri;
    }

    /**
     * Create DTO from full OAuth2AuthorizationRequest
     */
    public static OAuth2AuthorizationRequestDTO fromAuthorizationRequest(OAuth2AuthorizationRequest request) {
        // Extract client registration ID from attributes
        Map<String, Object> attributes = request.getAttributes();
        String clientRegistrationId = (String) attributes.get("registration_id");

        // Extract PKCE code verifier from attributes (Spring Security 6+ stores it here)
        String codeVerifier = (String) attributes.get("code_verifier");

        return new OAuth2AuthorizationRequestDTO(
                request.getState(),
                request.getRedirectUri(),
                clientRegistrationId,
                codeVerifier,
                request.getAuthorizationRequestUri()
        );
    }

    /**
     * Reconstruct minimal OAuth2AuthorizationRequest from DTO.
     *
     * Note: This creates a minimal request with only critical fields.
     * Spring Security will validate state and redirectUri during callback processing.
     * Other fields (scopes, authorizationUri, etc.) are not needed for validation.
     */
    public OAuth2AuthorizationRequest toAuthorizationRequest() {
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest.authorizationCode()
                .state(state)
                .redirectUri(redirectUri)
                .authorizationRequestUri(authorizationRequestUri != null ? authorizationRequestUri : redirectUri)
                .clientId(clientRegistrationId)
                .authorizationUri("https://oauth.placeholder.com/authorize"); // Placeholder, not used in callback

        // Add client registration ID and PKCE code verifier to attributes
        builder.attributes(attrs -> {
            attrs.put("registration_id", clientRegistrationId);
            // Add PKCE code verifier to attributes (Spring Security 6+ expects it here)
            if (codeVerifier != null) {
                attrs.put("code_verifier", codeVerifier);
            }
        });

        return builder.build();
    }
}
