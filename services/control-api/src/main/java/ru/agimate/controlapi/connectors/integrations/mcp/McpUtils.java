package ru.agimate.controlapi.connectors.integrations.mcp;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
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

    /**
     * A URL safe to log: scheme and host only. The user types the URL, and it may carry credentials
     * as userinfo or a query ({@code ?api_key=…}) — exactly the case
     * {@link ru.agimate.controlapi.connectors.core.dto.CredentialField#opens} keeps in the secret.
     */
    public static String logTarget(String url) {
        if (url == null) {
            return "none";
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) {
                return "invalid url";
            }
            String origin = uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() > 0 ? origin + ":" + uri.getPort() : origin;
        } catch (IllegalArgumentException e) {
            return "invalid url";
        }
    }

    /**
     * An exception text safe to log: URL userinfo and query removed, length bounded. The text comes
     * from the transport layer and may echo the request URL or an external response body; the full
     * message still travels to the connection's owner as the validation error.
     */
    public static String loggable(String message) {
        if (message == null) {
            return "no detail";
        }
        String redacted = message
                .replaceAll("(?i)://[^/@\\s\"']+@", "://")
                .replaceAll("\\?[^\\s\"']*", "?…");
        return redacted.length() > 200 ? redacted.substring(0, 200) + "…" : redacted;
    }
}
