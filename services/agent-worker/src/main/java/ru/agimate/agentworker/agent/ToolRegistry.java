package ru.agimate.agentworker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Mapping between LLM-facing tool names and backend connector tools for one agent run.
 *
 * <p>The model sees sanitized, namespaced names ({@code {namespace}.{name}} → dots replaced) so
 * two instances exposing a same-named tool stay distinguishable; dispatch resolves a sanitized
 * name back to {@code (connector_code, backend name, identity=connection_id)} so the wire call to
 * {@code ExecuteTool} is unchanged (routing by instance identity).
 */
public final class ToolRegistry {

    private static final Pattern UNSAFE_NAME_CHAR = Pattern.compile("[^A-Za-z0-9_-]");
    private static final String EMPTY_OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A connector instance and the tool specs it exposes. */
    public record ConnectorTools(String connectorCode, List<ConnectorToolSpec> tools) {}

    /** Resolved backend routing for a sanitized tool name. */
    public record BackendTool(String connectorCode, String name, String identity) {}

    private final List<ToolDef> toolDefs;
    private final Map<String, BackendTool> sanitizedToBackend;

    private ToolRegistry(List<ToolDef> toolDefs, Map<String, BackendTool> sanitizedToBackend) {
        this.toolDefs = toolDefs;
        this.sanitizedToBackend = sanitizedToBackend;
    }

    /** Reconstruct a registry from its serialized parts (used to restore a durable step result). */
    public static ToolRegistry of(List<ToolDef> toolDefs, Map<String, BackendTool> backendMap) {
        return new ToolRegistry(toolDefs, backendMap);
    }

    /** The sanitized-name → backend-routing map (for serializing a prepared context). */
    public Map<String, BackendTool> backendMap() {
        return sanitizedToBackend;
    }

    public static ToolRegistry build(List<ConnectorTools> loaded) {
        List<ToolDef> defs = new ArrayList<>();
        Map<String, BackendTool> map = new HashMap<>();
        for (ConnectorTools ct : loaded) {
            for (ConnectorToolSpec spec : ct.tools()) {
                String namespace = spec.getNamespace().isBlank() ? ct.connectorCode() : spec.getNamespace();
                String sanitized = sanitizeToolName(namespace + "." + spec.getName());
                map.put(sanitized, new BackendTool(ct.connectorCode(), spec.getName(), spec.getConnectionId()));
                defs.add(new ToolDef(sanitized, spec.getDescription(), parseToolSchema(spec)));
            }
        }
        return new ToolRegistry(defs, map);
    }

    /**
     * Make {@code name} safe for the OpenAI function-calling name field. Dots become {@code __}
     * so a namespaced name ({@code board.get_tasks}) does not collide with an underscored one
     * ({@code board_get_tasks}); any remaining unsafe character maps to {@code _}.
     */
    public static String sanitizeToolName(String name) {
        return UNSAFE_NAME_CHAR.matcher(name.replace(".", "__")).replaceAll("_");
    }

    /**
     * Parse the spec's MCP {@code input_schema}, falling back to an empty-object schema (OpenAI
     * strict mode rejects a bare {@code {}}; parameterless tools arrive as {@code {"type":"object"}}
     * with no properties).
     */
    public static String parseToolSchema(ConnectorToolSpec spec) {
        String raw = spec.getInputSchema().isEmpty() ? "" : spec.getInputSchema().toStringUtf8();
        if (raw.isBlank()) {
            return EMPTY_OBJECT_SCHEMA;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isObject() || node.isEmpty()) {
                return EMPTY_OBJECT_SCHEMA;
            }
            // {"type": "object"} with no properties → strict-mode-safe empty object.
            if (node.size() == 1 && "object".equals(node.path("type").asText())) {
                return EMPTY_OBJECT_SCHEMA;
            }
            return raw;
        } catch (Exception e) {
            return EMPTY_OBJECT_SCHEMA;
        }
    }

    public List<ToolDef> toolDefs() {
        return toolDefs;
    }

    /** Sanitized tool names the model may call (the registry's known keys). */
    public List<String> names() {
        return toolDefs.stream().map(ToolDef::name).toList();
    }

    /** Resolve a sanitized name to its backend routing; {@code null} if the model hallucinated it. */
    public BackendTool resolve(String sanitized) {
        return sanitizedToBackend.get(sanitized);
    }

    /** Backend tool name for display; falls back to the sanitized name. */
    public String displayName(String sanitized) {
        BackendTool bt = sanitizedToBackend.get(sanitized);
        return bt != null ? bt.name() : sanitized;
    }

    /** Backend display names for every tool call in an assistant message. */
    public List<String> displayNames(AgentChatMessage assistant) {
        return assistant.toolCalls().stream().map(tc -> displayName(tc.name())).toList();
    }
}
