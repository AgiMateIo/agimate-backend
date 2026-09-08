package ru.agimate.controlapi.connectors.internal.toolloader;

import com.google.protobuf.util.JsonFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolMeta;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.database.enums.Disclosure;
import ru.agimate.controlapi.grpc.mapper.RunContextMapper;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.service.runcontext.RunTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code load_tools}: the full specs of deferred tools, by the names the model read in the listing.
 * The scope is recomputed here from {@code env.runId} by the same {@link RunCatalog} that built the
 * run's context — the worker cannot ask for a schema the agent is not bound to.
 *
 * <p>The result is a context delta, not text for the model: the {@code _meta} marker tells the worker
 * to parse it, put the specs into the request's tool list and show the model only the names. The
 * specs are printed as proto JSON of the same {@code ConnectorToolSpec} that {@code GetRunContext}
 * carries — one shape, one mapper on each side ({@link RunContextMapper}, always the EAGER form).
 */
@Component
@RequiredArgsConstructor
public class ToolLoaderToolService {

    /** {@code _meta} key the worker reads to tell a context delta from an ordinary result. */
    public static final String META_CONTEXT_MATERIAL = "agimate.context_material";
    public static final String MATERIAL_TOOLS = "tools";

    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    private final RunCatalog runCatalog;

    @Tool(name = "load_tools", description = "Load the full definitions of deferred tools so they become "
            + "callable. Pass every tool you are going to need in one call: each call costs a turn.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = false),
            meta = @ToolMeta(key = META_CONTEXT_MATERIAL, value = MATERIAL_TOOLS))
    public Map<String, Object> loadTools(
            @ToolParam("Tool names exactly as listed under deferred_tools, e.g. [\"platform__agent_list\"]")
            List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new ConnectorException("names is required: the tool names from the deferred_tools listing");
        }
        RunCatalog.Catalog catalog = catalog(ConnectorEnvHolder.current());
        List<Map<String, Object>> tools = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String name : names) {
            RunTool tool = catalog.tool(name);
            if (tool == null) {
                unknown.add(name);
                continue;
            }
            tools.add(printed(tool));
        }
        if (tools.isEmpty()) {
            throw new ConnectorException("Unknown tools: " + String.join(", ", unknown)
                    + ". Deferred tools you can load: " + String.join(", ", deferredNames(catalog, unknown)));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        result.put("unknown", unknown);
        return result;
    }

    /** Inside a run — that run's scope; outside one (an MCP client, the manage listing) — the agent's. */
    private RunCatalog.Catalog catalog(ConnectorEnv env) {
        if (env.agentId() == null) {
            throw new ConnectorException("load_tools requires an agent context");
        }
        return env.runId() != null
                ? runCatalog.forRun(env.agentId(), env.runId())
                : runCatalog.forAgent(env.agentId());
    }

    /** The EAGER wire form as JSON: the worker parses it back into the same proto message. */
    private static Map<String, Object> printed(RunTool tool) {
        try {
            return JsonUtils.fromJsonToMap(PRINTER.print(RunContextMapper.toProto(tool.eager())));
        } catch (Exception e) {
            throw new ConnectorException("Cannot serialise tool " + tool.llmName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * The nearest names for a miss: deferred tools of the same namespaces the model tried, or all
     * deferred tools when the namespaces themselves were guessed. No fuzzy matching — the model has
     * the listing, it only needs to be pointed back at it.
     */
    private static List<String> deferredNames(RunCatalog.Catalog catalog, List<String> missed) {
        List<String> namespaces = missed.stream()
                .map(n -> n.contains("__") ? n.substring(0, n.indexOf("__")) : n)
                .distinct()
                .toList();
        List<String> deferred = catalog.tools().stream()
                .filter(t -> t.disclosure() == Disclosure.LAZY)
                .map(RunTool::llmName)
                .toList();
        List<String> near = deferred.stream()
                .filter(n -> namespaces.stream().anyMatch(ns -> n.startsWith(ns + "__")))
                .toList();
        return near.isEmpty() ? deferred : near;
    }
}
