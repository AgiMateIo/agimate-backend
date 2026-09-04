package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.GetTurnResponse;
import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.ToolCallRec;
import ru.agimate.agentworker.ToolResultRec;
import ru.agimate.agentworker.ToolTurn;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Projections of loop messages for the channel progress stream ({@code SaveMessage}); no DBOS or
 * transport here. A tool turn goes out as two lines: TOOL_CALL ({@link #progressLines}) with the
 * calls before the dispatch, TOOL_RESULT ({@link #toolResultLine}) with the results after it. Both
 * carry a structured {@link ToolTurn} besides the text — the {@code 🔧}-text alone taught models to
 * imitate tool calls as text instead of calling them.
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

    /** A ledger turn read back ({@code GetTurn}) as the message the loop would have built from the provider's reply. */
    public static AgentChatMessage fromTurn(GetTurnResponse turn) {
        return switch (turn.getRole()) {
            case TURN_ROLE_ASSISTANT -> AgentChatMessage.assistant(turn.getText(), turn.getThinking(),
                    turn.getToolCallsList().stream()
                            .map(c -> new AgentChatMessage.ToolCall(c.getId(), c.getName(), c.getArgumentsJson()))
                            .toList());
            case TURN_ROLE_TOOL -> AgentChatMessage.toolResults(turn.getToolResultsList().stream()
                    .map(r -> new AgentChatMessage.ToolResult(r.getId(), r.getName(), r.getOutputJson(), r.getFailed()))
                    .toList());
            case TURN_ROLE_USER -> AgentChatMessage.user(turn.getText());
            case TURN_ROLE_SYSTEM -> AgentChatMessage.system(turn.getText());
            case TURN_ROLE_UNSPECIFIED, UNRECOGNIZED ->
                    throw new IllegalStateException("ledger turn without a role");
        };
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
