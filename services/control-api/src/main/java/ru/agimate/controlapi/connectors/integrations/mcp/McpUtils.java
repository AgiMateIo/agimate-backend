package ru.agimate.controlapi.connectors.integrations.mcp;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.integrations.mcp.oauth.OAuthCredentials;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Any absolute URL, however it is embedded in the text. */
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b[a-z][a-z0-9+.\\-]*://[^\\s\"']*");

    /** Token-shaped runs: a JWT, a dotted key pair and any long opaque run are never useful in a log. */
    private static final Pattern JWT_PATTERN = Pattern.compile("eyJ[A-Za-z0-9._-]{10,}");
    private static final Pattern KEY_PAIR_PATTERN = Pattern.compile("[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}");
    private static final Pattern LONG_RUN_PATTERN = Pattern.compile("[A-Za-z0-9_-]{32,}");

    /**
     * An exception text safe to log. The text comes from the transport layer and may echo the request
     * URL (userinfo, path, query) or a token; every URL is reduced to its origin, token-shaped runs are
     * masked, and the result is bounded. The full message still travels to the connection's owner as
     * the validation error.
     */
    public static String loggable(String message) {
        if (message == null) {
            return "no detail";
        }
        Matcher urls = URL_PATTERN.matcher(message);
        StringBuilder stripped = new StringBuilder();
        while (urls.find()) {
            urls.appendReplacement(stripped, Matcher.quoteReplacement(logTarget(urls.group())));
        }
        urls.appendTail(stripped);
        String masked = JWT_PATTERN.matcher(stripped.toString()).replaceAll("<token>");
        masked = KEY_PAIR_PATTERN.matcher(masked).replaceAll("<token>");
        masked = LONG_RUN_PATTERN.matcher(masked).replaceAll("<token>");
        return masked.length() > 200 ? masked.substring(0, 200) + "…" : masked;
    }
}
