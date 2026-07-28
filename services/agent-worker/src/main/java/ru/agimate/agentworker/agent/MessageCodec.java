package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text projections of loop messages for the channel progress stream ({@code SaveMessage}).
 * No DBOS or transport here. History persistence is text-only since protocol v2 — the raw LLM
 * transcript lives in DBOS checkpoints, not in the session store. Exception (v2.1): the tool
 * turn additionally carries a structured {@link ToolTurn} — the backend feeds it to the next
 * runs' history as native tool_use/tool_result, because the {@code 🔧}-text projection teaches
 * the model to imitate tool calls as text instead of calling them. The turn is split across two
 * lines (v2.1a): the TOOL_CALL line ({@link #progressLines}) carries the calls and is emitted
 * before dispatch; the TOOL_RESULT line ({@link #toolResultLine}) carries the results afterwards.
 */
public final class MessageCodec {

    private static final String THINKING_EMOJI = "💭";
    private static final String TOOL_EMOJI = "🔧";

    /**
     * One channel-facing progress line with its kind (feeds SaveMessage progress_type).
     * {@code toolTurn} is non-null only on the TOOL_CALL line.
     */
    public record ProgressLine(ProgressType type, String text, ToolTurn toolTurn) {

        public ProgressLine(ProgressType type, String text) {
            this(type, text, null);
        }
    }

    private MessageCodec() {
    }

    /**
     * Channel-facing progress lines for one assistant message: a thinking marker (if it reasoned),
     * the preamble text written alongside tool calls, and one {@code 🔧 <name>} line per tool. Text
     * is emitted only when tools are present, so the final tool-less answer is not echoed here — it
     * is sent once as the ANSWER after the loop. The TOOL_CALL line carries a calls-only
     * {@link ToolTurn} (the {@code tool_use} half) — it is emitted before dispatch, so results are
     * not available yet; they arrive separately via {@link #toolResultLine}.
     */
    public static List<ProgressLine> progressLines(AgentChatMessage assistant, List<String> toolDisplayNames) {
        List<ProgressLine> lines = new ArrayList<>();
        if (assistant.thinking()) {
            lines.add(new ProgressLine(ProgressType.PROGRESS_TYPE_THINKING, THINKING_EMOJI + " thinking..."));
        }
        if (toolDisplayNames == null || toolDisplayNames.isEmpty()) {
            return lines;
        }
        if (assistant.text() != null && !assistant.text().isEmpty()) {
            lines.add(new ProgressLine(ProgressType.PROGRESS_TYPE_TEXT, assistant.text()));
        }
        StringBuilder toolLines = new StringBuilder();
        for (int i = 0; i < toolDisplayNames.size(); i++) {
            if (i > 0) {
                toolLines.append("\n");
            }
            toolLines.append(TOOL_EMOJI).append(" ").append(toolDisplayNames.get(i));
        }
        lines.add(new ProgressLine(ProgressType.PROGRESS_TYPE_TOOL_CALL, toolLines.toString(),
                callsTurn(assistant)));
        return lines;
    }

    /**
     * The {@code tool_result} half of a tool turn: a results-only {@link ToolTurn} on a
     * TOOL_RESULT line with empty text (history-only, not delivered to the channel). Emitted after
     * the tools ran, so it lands as a separate record right after the TOOL_CALL line.
     */
    public static ProgressLine toolResultLine(AgentChatMessage toolResults) {
        ToolTurn turn = ToolTurn.newBuilder().setText("")
                .addAllResults(toolResultRecs(toolResults.toolResults()))
                .build();
        return new ProgressLine(ProgressType.PROGRESS_TYPE_TOOL_RESULT, "", turn);
    }

    /** Assistant calls as proto records (shared converter for the channel projection and the turn journal). */
    public static List<ToolCallRec> toolCallRecs(List<AgentChatMessage.ToolCall> calls) {
        List<ToolCallRec> recs = new ArrayList<>(calls.size());
        for (AgentChatMessage.ToolCall call : calls) {
            recs.add(ToolCallRec.newBuilder()
                    .setId(nullToEmpty(call.id()))
                    .setName(nullToEmpty(call.name()))
                    .setArgumentsJson(nullToEmpty(call.argumentsJson()))
                    .build());
        }
        return recs;
    }

    /** Tool results as proto records (shared converter for the channel projection and the turn journal). */
    public static List<ToolResultRec> toolResultRecs(List<AgentChatMessage.ToolResult> results) {
        List<ToolResultRec> recs = new ArrayList<>(results.size());
        for (AgentChatMessage.ToolResult result : results) {
            recs.add(ToolResultRec.newBuilder()
                    .setId(nullToEmpty(result.id()))
                    .setName(nullToEmpty(result.name()))
                    .setOutputJson(nullToEmpty(result.contentJson()))
                    .setFailed(result.failed())
                    .build());
        }
        return recs;
    }

    /** The {@code tool_use} half of a turn: preamble plus the assistant's calls, without results. */
    private static ToolTurn callsTurn(AgentChatMessage assistant) {
        return ToolTurn.newBuilder()
                .setText(assistant.text() != null ? assistant.text() : "")
                .addAllCalls(toolCallRecs(assistant.toolCalls()))
                .build();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
