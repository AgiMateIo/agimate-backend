package ru.agimate.agentworker.agent.context;

import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.FilePart;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.PromptBlock;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.ToolRegistry;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure renderer of the backend-assembled context: turns ordered {@link ContextMaterials} blocks
 * into the system prompt and the user turn of a {@link PreparedContext}. The assembly policy
 * (what blocks exist, their order, trust and ephemerality) is decided by the backend
 * ({@code RunContextService}); this class only puts tags around content. No gRPC, no DBOS.
 *
 * <p>Rendering rules: a block with an empty {@code name} is raw text; a named trusted block is
 * wrapped in {@code <name attrs>…</name>}; an untrusted block additionally gets a preamble
 * pinning it as data, and closing tags inside its content are neutralized so the payload cannot
 * break out of the wrapper. The wording of the preamble and of the guidance paragraphs comes from
 * {@link ResponseTemplates} ({@code prompt.*}).
 */
@Slf4j
public final class ContextBuilder {

    /** Wrapper tag for open-world tool output; applied by {@code ToolCallDispatcher}. */
    public static final String UNTRUSTED_TOOL_OUTPUT_TAG = "untrusted_tool_output";

    private final ResponseTemplates templates;

    public ContextBuilder(ResponseTemplates templates) {
        this.templates = templates;
    }

    public PreparedContext build(ContextMaterials materials) {
        String systemPrompt = render(materials.systemBlocks());
        // Open-world tool output is third-party content (mail, tickets, the web) — a prompt-injection channel.
        if (materials.tools().stream().anyMatch(t -> t.getAnnotations().getOpenWorldHint())) {
            systemPrompt = systemPrompt + "\n\n" + templates.toolOutputGuidance(UNTRUSTED_TOOL_OUTPUT_TAG);
        }
        if (!materials.tools().isEmpty()) {
            systemPrompt = systemPrompt + "\n\n" + templates.detachedToolGuidance();
        }
        String userPrompt = render(materials.userBlocks().stream()
                .filter(b -> !b.getEphemeral()).toList());
        List<PromptBlock> ephemeral = materials.userBlocks().stream()
                .filter(PromptBlock::getEphemeral).toList();
        String ephemeralPrefix = ephemeral.isEmpty() ? null : render(ephemeral);

        ToolRegistry registry = ToolRegistry.build(materials.tools());
        List<AgentChatMessage> history = mapHistory(materials.history());
        List<FilePartRef> inboundParts = mapParts(materials.inboundParts());
        log.info("context ready: {} system / {} user block(s), {} tool(s), {} history msg(s), {} part(s)",
                materials.systemBlocks().size(), materials.userBlocks().size(),
                registry.toolDefs().size(), history.size(), inboundParts.size());
        log.debug("tools: {}", registry.names());

        return new PreparedContext(systemPrompt, userPrompt, ephemeralPrefix, history, registry, inboundParts);
    }

    /** proto {@code FilePart} → the worker's {@link FilePartRef} (references only; bytes come via GetFile). */
    public static List<FilePartRef> mapParts(List<FilePart> parts) {
        List<FilePartRef> refs = new ArrayList<>(parts.size());
        for (FilePart p : parts) {
            refs.add(new FilePartRef(p.getFileId(), p.getType(), p.getMime(), p.getSize(), p.getName()));
        }
        return refs;
    }

    /**
     * Ledger history as model turns: INBOUND → user, everything else → assistant text. A tool turn
     * carrying {@code tool_turn} is expanded into the native pair {@code assistant(tool_calls)} +
     * {@code tool(results)} — the model sees past calls through the same channel it is required to
     * call through, rather than as imitated text.
     *
     * <p>A turn arrives as two adjacent records: first calls (tool_use), then results (tool_result);
     * the calls record consumes the following results record by look-ahead. An orphaned results
     * record (its calls half was cut off by the history window) is dropped — a {@code tool} with no
     * preceding {@code tool_use} is rejected by providers.
     */
    static List<AgentChatMessage> mapHistory(List<HistoryMessage> history) {
        List<AgentChatMessage> mapped = new ArrayList<>(history.size());
        for (int i = 0; i < history.size(); i++) {
            HistoryMessage m = history.get(i);
            ToolTurn turn = m.hasToolTurn() ? m.getToolTurn() : null;
            if (turn != null && turn.getCallsCount() > 0) {
                // The calls record never carries its own results — they live in the next entry.
                List<ToolResultRec> results = List.of();
                if (i + 1 < history.size()) {
                    HistoryMessage next = history.get(i + 1);
                    if (next.hasToolTurn() && next.getToolTurn().getCallsCount() == 0
                            && next.getToolTurn().getResultsCount() > 0) {
                        results = next.getToolTurn().getResultsList();
                        i++;
                    }
                }
                mapToolTurn(turn.getText(), turn.getCallsList(), results, mapped);
                continue;
            }
            if (turn != null && turn.getResultsCount() > 0) {
                continue; // an orphaned results record with no calls half
            }
            if (m.getText().isBlank()) {
                continue;
            }
            mapped.add(m.getKind() == MessageKind.MESSAGE_KIND_INBOUND
                    ? AgentChatMessage.user(m.getText())
                    : AgentChatMessage.assistant(m.getText(), false, List.of()));
        }
        return mapped;
    }

    /**
     * The native pair of a tool turn. A result is mandatory for every call (providers reject a
     * tool_use with no answer) — when a record is missing, an {@code {"error": ...}} stub is put in
     * its place.
     */
    private static void mapToolTurn(String text, List<ToolCallRec> callRecs,
                                    List<ToolResultRec> resultRecs, List<AgentChatMessage> mapped) {
        List<AgentChatMessage.ToolCall> calls = callRecs.stream()
                .map(c -> new AgentChatMessage.ToolCall(c.getId(), c.getName(), c.getArgumentsJson()))
                .toList();
        Map<String, AgentChatMessage.ToolResult> byId = new java.util.HashMap<>();
        for (ToolResultRec r : resultRecs) {
            byId.put(r.getId(), new AgentChatMessage.ToolResult(
                    r.getId(), r.getName(), r.getOutputJson(), r.getFailed()));
        }
        List<AgentChatMessage.ToolResult> results = calls.stream()
                .map(c -> byId.getOrDefault(c.id(), new AgentChatMessage.ToolResult(
                        c.id(), c.name(), "{\"error\": \"result not recorded\"}", true)))
                .toList();
        mapped.add(AgentChatMessage.assistant(text.isBlank() ? null : text, false, calls));
        mapped.add(AgentChatMessage.toolResults(results));
    }

    /** Blocks joined by a blank line, each rendered per the trust/name rules. Order untouched. */
    String render(List<PromptBlock> blocks) {
        List<String> parts = new ArrayList<>(blocks.size());
        for (PromptBlock block : blocks) {
            parts.add(renderBlock(block));
        }
        return String.join("\n\n", parts);
    }

    private String renderBlock(PromptBlock block) {
        if (!block.getTrusted()) {
            return renderUntrusted(block);
        }
        if (block.getName().isBlank()) {
            return block.getContent();
        }
        return openTag(block) + "\n" + block.getContent() + "\n</" + block.getName() + ">";
    }

    private String renderUntrusted(PromptBlock block) {
        String tag = block.getName().isBlank() ? "untrusted_data" : block.getName();
        String content = neutralizeClosingTag(block.getContent(), tag);
        return templates.untrustedPreamble(tag) + "\n"
                + openTag(tag, block.getAttrsMap()) + "\n" + content + "\n</" + tag + ">";
    }

    /**
     * Neutralises a closing tag inside the data — case- and whitespace-insensitively
     * (</tag>, </Tag>, </ tag >): a payload cannot escape its wrapper through such variations.
     */
    public static String neutralizeClosingTag(String content, String tag) {
        return Pattern.compile("(?i)</\\s*" + Pattern.quote(tag) + "\\s*>")
                .matcher(content)
                .replaceAll(Matcher.quoteReplacement("</ " + tag + ">"));
    }

    private static String openTag(PromptBlock block) {
        return openTag(block.getName(), block.getAttrsMap());
    }

    /** Attributes in sorted order — rendering stays deterministic regardless of map ordering. */
    private static String openTag(String name, Map<String, String> attrs) {
        StringBuilder tag = new StringBuilder("<").append(name);
        for (Map.Entry<String, String> attr : new TreeMap<>(attrs).entrySet()) {
            tag.append(' ').append(attr.getKey()).append("=\"")
                    .append(attr.getValue().replace("\"", "&quot;")).append('"');
        }
        return tag.append('>').toString();
    }
}
