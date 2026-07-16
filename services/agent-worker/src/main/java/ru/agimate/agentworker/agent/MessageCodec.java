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
 * the model to imitate tool calls as text instead of calling them.
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
     * is sent once as the ANSWER after the loop. {@code toolResults} (nullable) is the same turn's
     * tool-result message — together with the assistant's calls it forms the structured
     * {@link ToolTurn} attached to the TOOL_CALL line.
     */
    public static List<ProgressLine> progressLines(AgentChatMessage assistant, List<String> toolDisplayNames,
                                                   AgentChatMessage toolResults) {
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
                toolTurn(assistant, toolResults)));
        return lines;
    }

    /** Структурная запись tool-хода: преамбула + вызовы ассистента + результаты (если уже есть). */
    private static ToolTurn toolTurn(AgentChatMessage assistant, AgentChatMessage toolResults) {
        ToolTurn.Builder turn = ToolTurn.newBuilder()
                .setText(assistant.text() != null ? assistant.text() : "");
        for (AgentChatMessage.ToolCall call : assistant.toolCalls()) {
            turn.addCalls(ToolCallRec.newBuilder()
                    .setId(nullToEmpty(call.id()))
                    .setName(nullToEmpty(call.name()))
                    .setArgumentsJson(nullToEmpty(call.argumentsJson())));
        }
        if (toolResults != null) {
            for (AgentChatMessage.ToolResult result : toolResults.toolResults()) {
                turn.addResults(ToolResultRec.newBuilder()
                        .setId(nullToEmpty(result.id()))
                        .setName(nullToEmpty(result.name()))
                        .setOutputJson(nullToEmpty(result.contentJson()))
                        .setFailed(result.failed()));
            }
        }
        return turn.build();
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
