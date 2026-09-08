package ru.agimate.agentworker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.Disclosure;
import ru.agimate.agentworker.ToolNames;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ContextMaterial;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mapping between LLM-facing tool names and backend connector tools for one agent run.
 *
 * <p>The model sees the name the backend computed ({@code llm_name}: {@code {namespace}.{name}}
 * sanitized, collisions resolved) so two instances exposing a same-named tool stay distinguishable;
 * dispatch resolves it back to {@code (connector_code, backend name, connectionId)} so the wire call
 * to {@code ExecuteTool} is unchanged. Against an older control-api the name is empty and the
 * worker sanitizes it itself — the same rule, the same result.
 *
 * <p>Progressive disclosure: a LAZY spec arrives without a schema and is routed but not callable —
 * it is not in {@link #toolDefs()} until a {@code describe_tools} result {@link #disclose discloses}
 * its full spec. The registry therefore grows during a run; the loop reads {@link #toolDefs()}
 * afresh every turn. Nothing here is checkpointed: a replay rebuilds it from {@code GetRunContext}
 * and re-applies the deltas it re-reads from the backend.
 */
@Slf4j
public final class ToolRegistry {

    /** {@code _meta} key marking a tool whose result is a context delta; values {@code tools} | {@code skill}. */
    public static final String META_CONTEXT_MATERIAL = "agimate.context_material";

    private static final String EMPTY_OBJECT_SCHEMA =
            "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Resolved backend routing for an LLM-facing tool name. {@code openWorld} — MCP
     * {@code openWorldHint}: the tool's output is external-world content and gets the
     * untrusted wrapper before entering the dialogue. {@code timeoutSeconds} — the spec's
     * wait budget for the tool result; {@code 0} means the worker default. {@code material} —
     * what the tool's result is to the context.
     */
    public record BackendTool(String connectorCode, String name, String connectionId, boolean openWorld,
                              int timeoutSeconds, ContextMaterial material) {}

    /** Every tool of the run, LAZY ones included, by LLM-facing name — insertion order is the backend's. */
    private final Map<String, BackendTool> routing = new LinkedHashMap<>();
    /** The callable subset: EAGER from the start, LAZY once disclosed. */
    private final Map<String, ToolDef> callable = new LinkedHashMap<>();

    private ToolRegistry() {
    }

    /** Build from the flat {@code GetRunContext} tool list: each spec carries its own routing. */
    public static ToolRegistry build(List<ConnectorToolSpec> specs) {
        ToolRegistry registry = new ToolRegistry();
        for (ConnectorToolSpec spec : specs) {
            String name = registry.register(spec);
            if (spec.getDisclosure() != Disclosure.DISCLOSURE_LAZY) {
                registry.callable.put(name, toolDef(name, spec));
            }
        }
        return registry;
    }

    /**
     * Apply a {@code describe_tools} delta: the specs become callable with their full schema. A spec
     * the run context never listed is registered too — the backend is the authority on the scope.
     *
     * @return the LLM-facing names of every spec in the delta, already-callable ones included
     */
    public List<String> disclose(List<ConnectorToolSpec> specs) {
        List<String> names = new ArrayList<>(specs.size());
        for (ConnectorToolSpec spec : specs) {
            String name = llmName(spec);
            if (!routing.containsKey(name)) {
                name = register(spec);
            }
            callable.put(name, toolDef(name, spec));
            names.add(name);
        }
        log.info("disclosed {} tool(s): {}", names.size(), names);
        return names;
    }

    private String register(ConnectorToolSpec spec) {
        String name = spec.getLlmName().isBlank() ? fallbackName(spec) : spec.getLlmName();
        routing.put(name, new BackendTool(spec.getConnectorCode(), spec.getName(), spec.getConnectionId(),
                spec.getAnnotations().getOpenWorldHint(), spec.getTimeoutSeconds(),
                ContextMaterial.fromMeta(spec.getMetaMap().get(META_CONTEXT_MATERIAL))));
        return name;
    }

    /** The backend's name when it sent one, otherwise the worker's own sanitization of {@code {namespace}.{name}}. */
    private static String llmName(ConnectorToolSpec spec) {
        return spec.getLlmName().isBlank() ? ToolNames.sanitize(fullName(spec)) : spec.getLlmName();
    }

    /** The older-control-api path: the shared rule, collisions suffixed in the backend's spec order. */
    private String fallbackName(ConnectorToolSpec spec) {
        String sanitized = ToolNames.sanitize(fullName(spec));
        String name = ToolNames.unique(routing.keySet(), sanitized);
        if (!name.equals(sanitized)) {
            log.warn("tool name collision after sanitizing: {} → {}", sanitized, name);
        }
        return name;
    }

    private static String fullName(ConnectorToolSpec spec) {
        String namespace = spec.getNamespace().isBlank() ? spec.getConnectorCode() : spec.getNamespace();
        return namespace + "." + spec.getName();
    }

    private static ToolDef toolDef(String name, ConnectorToolSpec spec) {
        return new ToolDef(name, spec.getDescription(), parseToolSchema(spec));
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

    /** The tools the model may call right now — a snapshot, re-read by the loop every turn. */
    public List<ToolDef> toolDefs() {
        return List.copyOf(callable.values());
    }

    /** LLM-facing names the model may call (the callable subset). */
    public List<String> names() {
        return List.copyOf(callable.keySet());
    }

    /**
     * Resolve an LLM-facing name to its backend routing; {@code null} if the model hallucinated it
     * — or named a LAZY tool it has not had described ({@link #deferred}): without the schema the
     * arguments are a guess.
     */
    public BackendTool resolve(String name) {
        return callable.containsKey(name) ? routing.get(name) : null;
    }

    /** A name the model read in the listing but has not had described yet: routed, not callable. */
    public boolean deferred(String name) {
        return routing.containsKey(name) && !callable.containsKey(name);
    }

    /** Backend tool name for display; falls back to the LLM-facing name. */
    public String displayName(String name) {
        BackendTool bt = routing.get(name);
        return bt != null ? bt.name() : name;
    }

    /** Backend display names for every tool call in an assistant message. */
    public List<String> displayNames(AgentChatMessage assistant) {
        return assistant.toolCalls().stream().map(tc -> displayName(tc.name())).toList();
    }
}
