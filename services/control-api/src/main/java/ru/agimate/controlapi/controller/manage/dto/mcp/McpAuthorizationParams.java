package ru.agimate.controlapi.controller.manage.dto.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import ru.agimate.controlapi.service.mcp.oauth.AuthorizationRequest;

/**
 * The query of the authorization request, passed through by the consent page under its OAuth names.
 * Nothing is validated here: which failures the page shows and which go back to the client is the
 * service's decision.
 */
@Schema(description = "Query parameters of an OAuth authorization request, as the consent page received them")
public record McpAuthorizationParams(
        @JsonProperty("response_type") String responseType,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("redirect_uri") String redirectUri,
        @JsonProperty("code_challenge") String codeChallenge,
        @JsonProperty("code_challenge_method") String codeChallengeMethod,
        @JsonProperty("state") String state,
        @JsonProperty("resource") String resource,
        @JsonProperty("scope") String scope
) {

    public AuthorizationRequest toRequest() {
        return new AuthorizationRequest(responseType, clientId, redirectUri, codeChallenge, codeChallengeMethod,
                state, resource, scope);
    }
}
