package ru.agimate.agentworker.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.ConnectorToolSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The {@code load_tools} result as the worker reads it. The backend answers
 * {@code {"tools": [<ConnectorToolSpec as proto JSON>], "unknown": [...]}} — the same specs it puts
 * into {@code GetRunContext}, so there is one shape and one mapper on each side. The model never
 * sees the schemas in the message: they go into the request's {@code tools} array, and the message
 * carries only the names ({@link #modelFacing}) — the compact JSON the backend's history assembler
 * reads back off the ledger.
 *
 * @param tools   the disclosed specs, full schema included
 * @param unknown names the backend could not resolve — passed to the model as they came
 */
@Slf4j
public record ContextDelta(List<ConnectorToolSpec> tools, List<String> unknown) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();

    /** @throws IllegalArgumentException when the output is not a delta — the caller falls back to the raw text */
    public static ContextDelta parse(String rawOutput) {
        JsonNode root;
        try {
            root = MAPPER.readTree(rawOutput);
        } catch (Exception e) {
            throw new IllegalArgumentException("context delta is not JSON: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject() || !root.path("tools").isArray()) {
            throw new IllegalArgumentException("context delta has no 'tools' array");
        }
        List<String> unknown = new ArrayList<>();
        for (JsonNode node : root.path("unknown")) {
            unknown.add(node.asText());
        }
        // A spec that does not parse is dropped, not fatal: the rest of the batch is still worth
        // disclosing, and the model reads the name back under «unknown» — the same answer it gets
        // for a name the backend could not resolve. Failing the whole delta would hand it a wall of
        // schema JSON instead.
        List<ConnectorToolSpec> tools = new ArrayList<>();
        for (JsonNode node : root.get("tools")) {
            ConnectorToolSpec.Builder builder = ConnectorToolSpec.newBuilder();
            try {
                PARSER.merge(MAPPER.writeValueAsString(node), builder);
            } catch (Exception e) {
                String name = node.path("llmName").asText(node.path("name").asText("?"));
                log.warn("context delta drops tool {}: {}", name, e.getMessage());
                unknown.add(name);
                continue;
            }
            tools.add(builder.build());
        }
        return new ContextDelta(tools, unknown);
    }

    /** {@code {"disclosed": [names], "unknown": [names]}} — what the model reads instead of the specs. */
    public static String modelFacing(List<String> disclosed, List<String> unknown) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode d = out.putArray("disclosed");
        disclosed.forEach(d::add);
        if (!unknown.isEmpty()) {
            ArrayNode u = out.putArray("unknown");
            unknown.forEach(u::add);
        }
        return out.toString();
    }
}
