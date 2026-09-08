package ru.agimate.controlapi.service.runcontext;

import lombok.experimental.UtilityClass;
import ru.agimate.common.util.JsonUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The byte budget of an assembled run context, as one log line: prompt blocks by name, tools by
 * instance (description and schema apart — the two halves progressive disclosure splits), history.
 * Bytes, not tokens: the conversion rate is the model's, the doc uses 3.5–4 chars per token.
 * Serialises every schema, so it is only ever built behind {@code isDebugEnabled}.
 */
@UtilityClass
class ContextSizeReport {

    static String of(RunContextView view) {
        StringBuilder out = new StringBuilder();
        out.append("system=").append(blocks(view.systemBlocks()));
        out.append(" user=").append(blocks(view.userBlocks()));
        out.append(" tools=").append(tools(view.tools()));
        out.append(" history=").append(view.history().size()).append(" msg ")
                .append(view.history().stream().mapToInt(ContextSizeReport::bytes).sum()).append(" B");
        return out.toString();
    }

    /** Text plus the tool turn's arguments and results — what the worker hands the model, after the cap. */
    private static int bytes(RunHistoryMessage message) {
        int size = bytes(message.text());
        if (message.toolTurn() != null) {
            size += message.toolTurn().calls().stream().mapToInt(c -> bytes(c.argumentsJson())).sum()
                    + message.toolTurn().results().stream().mapToInt(r -> bytes(r.outputJson())).sum();
        }
        return size;
    }

    /** {@code 1234 B (agent=…, skill=…)} — sizes by block name; unnamed blocks count under their source. */
    private static String blocks(List<RunBlock> blocks) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        int total = 0;
        for (RunBlock block : blocks) {
            int size = bytes(block.content());
            total += size;
            String key = block.name().isEmpty() ? block.source() : block.name();
            byName.merge(key, size, Integer::sum);
        }
        return total + " B " + byName;
    }

    /** {@code 27 (desc 1234 B, schema 5678 B) {platform/…=79:…}} — per instance, so MCP servers show apart. */
    private static String tools(List<RunTool> tools) {
        Map<String, int[]> byInstance = new LinkedHashMap<>();
        int desc = 0;
        int schema = 0;
        for (RunTool tool : tools) {
            int d = bytes(tool.spec().description());
            int s = tool.spec().inputSchema() == null ? 0 : bytes(JsonUtils.writeValueAsString(tool.spec().inputSchema()));
            desc += d;
            schema += s;
            int[] acc = byInstance.computeIfAbsent(tool.namespace(), k -> new int[3]);
            acc[0]++;
            acc[1] += d;
            acc[2] += s;
        }
        StringBuilder out = new StringBuilder();
        out.append(tools.size()).append(" (desc ").append(desc).append(" B, schema ").append(schema).append(" B) {");
        byInstance.forEach((ns, acc) -> out.append(ns).append('=').append(acc[0])
                .append(':').append(acc[1]).append('/').append(acc[2]).append("B "));
        return out.append('}').toString();
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
