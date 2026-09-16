package ru.agimate.controlapi.connectors.core;

import com.fasterxml.jackson.databind.JsonNode;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.dto.ViewCallResult;
import ru.agimate.controlapi.connectors.core.dto.ViewPage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link ViewProvider} of an internal connector: the page of {@code ui://<connector code>/<name>} is the
 * classpath resource {@code views/<connector code>/<name>.html}. A page is code, not seed — it ships with
 * the deploy and is reviewed in a PR, so there is no seed-only-if-missing to trip over.
 */
public interface ClasspathViewProvider extends ViewProvider {

    String MIME_TYPE = "text/html;profile=mcp-app";

    /** Keeps a uri from walking out of the connector's own directory. */
    Pattern VIEW_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    String connectorCode();

    @Override
    default ViewPage readView(ConnectorEnv env, String uri) {
        String prefix = "ui://" + connectorCode() + "/";
        String name = uri.startsWith(prefix) ? uri.substring(prefix.length()) : null;
        if (name == null || !VIEW_NAME.matcher(name).matches()) {
            throw new ConnectorException("Not a view of " + connectorCode() + ": " + uri);
        }
        String path = "views/" + connectorCode() + "/" + name + ".html";
        try (InputStream page = getClass().getClassLoader().getResourceAsStream(path)) {
            if (page == null) {
                throw new ConnectorException("View not found: " + uri);
            }
            return new ViewPage(MIME_TYPE, new String(page.readAllBytes(), StandardCharsets.UTF_8), null, null);
        } catch (IOException e) {
            throw new ConnectorException("View could not be read: " + uri);
        }
    }

    /** An internal tool returns a JSON object: the page gets it as {@code structuredContent}, the text alongside. */
    @Override
    default ViewCallResult toViewResult(String output) {
        String text = output == null ? "" : output;
        JsonNode node = JsonUtils.toJsonNodeOrNull(text);
        Object structured = node != null && node.isObject()
                ? JsonUtils.MAPPER.convertValue(node, JsonUtils.MAP_TYPE_REFERENCE)
                : null;
        return new ViewCallResult(List.of(Map.of("type", "text", "text", text)), structured, false);
    }
}
