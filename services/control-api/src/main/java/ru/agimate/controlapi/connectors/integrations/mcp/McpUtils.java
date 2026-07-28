package ru.agimate.controlapi.connectors.integrations.mcp;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.Map;

/**
 * Constants and parsing of the MCP connector's credentials.
 *
 * <p>credentials: {@code url} (the Streamable HTTP endpoint, mandatory), {@code auth_token} (Bearer,
 * optional), {@code headers} (a JSON object of arbitrary headers, optional).
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
                credentials.get(FIELD_AUTH_TOKEN),
                parseHeaders(credentials.get(FIELD_HEADERS)));
    }

    private static Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            return JsonUtils.MAPPER.readValue(headersJson, JsonUtils.MAP_STRING_TYPE_REFERENCE);
        } catch (Exception e) {
            throw new ConnectorException("Invalid 'headers' JSON: " + e.getMessage());
        }
    }
}
