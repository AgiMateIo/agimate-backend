package ru.agimate.agentworker.agent;

import ru.agimate.agentworker.ProgressType;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure text projections of loop messages for the channel progress stream ({@code SaveMessage}).
 * No DBOS or transport here. History persistence is text-only since protocol v2 — the raw LLM
 * transcript lives in DBOS checkpoints, not in the session store.
 */
public final class MessageCodec {

    private static final String THINKING_EMOJI = "💭";
    private static final String TOOL_EMOJI = "🔧";

    /** One channel-facing progress line with its kind (feeds SaveMessage progress_type). */
    public record ProgressLine(ProgressType type, String text) {}

    private MessageCodec() {
    }

    /**
     * Channel-facing progress lines for one assistant message: a thinking marker (if it reasoned),
     * the preamble text written alongside tool calls, and one {@code 🔧 <name>} line per tool. Text
     * is emitted only when tools are present, so the final tool-less answer is not echoed here — it
     * is sent once as the ANSWER after the loop.
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
        lines.add(new ProgressLine(ProgressType.PROGRESS_TYPE_TOOL_CALL, toolLines.toString()));
        return lines;
    }
}
