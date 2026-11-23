package ru.agimate.controlapi.connectors.integrations.mcp;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Constants and parsing of the MCP connector's credentials.
 *
 * <p>credentials: {@code url} (the Streamable HTTP endpoint, mandatory), {@code auth_token} (Bearer,
 * optional), {@code headers} (a JSON object of arbitrary headers, optional). A connection authorised
 * over OAuth carries its token under {@link OAuthCredentials#ACCESS_TOKEN} instead.
 */
@UtilityClass
public class McpUtils {

    public static final String CONNECTOR_CODE = "mcp";

    public static final String FIELD_URL = "url";
    public static final String FIELD_AUTH_TOKEN = "auth_token";
    public static final String FIELD_HEADERS = "headers";

    /** Assembles a {@link McpClient.ServerConfig} from the decrypted credentials. */
    public static McpClient.ServerConfig toServerConfig(Map<String, String> credentials) {
        String url = credentials.get(FIELD_URL);
        if (url == null || url.isBlank()) {
            throw new ConnectorException("MCP server url is required");
        }
        return new McpClient.ServerConfig(
                url.trim(),
                bearer(credentials),
                parseHeaders(credentials.get(FIELD_HEADERS)));
    }

    /**
     * The grant wins over a static token: an OAuth connection exists only because the server answered
     * 401 to whatever static token was given, so that token is known not to work.
     *
     * <p>Both fields are declared SECRET, and {@link ru.agimate.controlapi.connectors.core.dto.CredentialField}
     * trims only URL and TEXT — so a token pasted with a trailing space or newline reaches the server
     * as is and is rejected. The edges are trimmed here, at the only place that builds a request.
     */
    private static String bearer(Map<String, String> credentials) {
        String accessToken = OAuthCredentials.accessToken(credentials);
        if (OAuthCredentials.isOAuth(credentials) && accessToken != null && !accessToken.isBlank()) {
            return accessToken.trim();
        }
        String token = credentials.get(FIELD_AUTH_TOKEN);
        return token == null || token.isBlank() ? null : token.trim();
    }

    /** Parsed as is, then trimmed: whitespace around a pasted value is never part of a header. */
    private static Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        Map<String, String> parsed;
        try {
            parsed = JsonUtils.MAPPER.readValue(headersJson, JsonUtils.MAP_STRING_TYPE_REFERENCE);
        } catch (Exception e) {
            throw new ConnectorException("Invalid 'headers' JSON: " + e.getMessage());
        }
        Map<String, String> headers = new LinkedHashMap<>();
        parsed.forEach((name, value) -> {
            if (name != null && !name.isBlank() && value != null) {
                headers.put(name.trim(), value.trim());
            }
        });
        return headers;
    }
}
