package ru.agimate.controlapi.service.session.compaction;

import lombok.experimental.UtilityClass;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs of a conversation as plain text for a model to retell. Not the history the worker replays:
 * no native tool pairs and no reasoning, because the reader continues nothing — it reads a record of
 * what was said and done.
 */
@UtilityClass
class SessionTranscript {

    /** One argument or result JSON in the transcript; the same cap the history window uses. */
    static final int TOOL_JSON_CAP = 4 * 1024;

    static final String OMITTED = "[the earlier part of the conversation is omitted]";

    /**
     * The runs, oldest first, each as its own block; blocks are dropped from the oldest end once
     * {@code budgetChars} is spent — the newest part is what the summary must not lose.
     *
     * @param runs the turns of each run, runs oldest first
     */
    static String render(List<List<AgentRunTurn>> runs, int budgetChars) {
        List<String> kept = new ArrayList<>();
        int spent = 0;
        for (int i = runs.size() - 1; i >= 0; i--) {
            String block = renderRun(runs.get(i));
            if (block.isEmpty()) {
                continue;
            }
            if (spent + block.length() > budgetChars && !kept.isEmpty()) {
                kept.add(OMITTED);
                break;
            }
            spent += block.length();
            kept.add(block);
        }
        StringBuilder text = new StringBuilder();
        for (int i = kept.size() - 1; i >= 0; i--) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(kept.get(i));
        }
        return text.toString();
    }

    static String renderRun(List<AgentRunTurn> turns) {
        StringBuilder text = new StringBuilder();
        for (AgentRunTurn turn : turns) {
            switch (turn.getRole()) {
                case USER -> line(text, "User", turn.getText());
                case ASSISTANT -> {
                    line(text, "Agent", turn.getText());
                    for (Map<String, Object> row : rows(turn.getToolCalls())) {
                        ToolTurnRecord.Call call = ToolTurnRecord.Call.fromRow(row);
                        line(text, "Agent called", call.name() + " " + cap(call.argumentsJson()));
                    }
                }
                case TOOL -> {
                    for (Map<String, Object> row : rows(turn.getToolResults())) {
                        ToolTurnRecord.Result result = ToolTurnRecord.Result.fromRow(row);
                        line(text, result.failed() ? "Tool failed" : "Tool result",
                                result.name() + " → " + cap(result.outputJson()));
                    }
                }
                case SYSTEM -> { } // an older summary is handed over on its own
            }
        }
        return text.toString();
    }

    private static void line(StringBuilder text, String who, String what) {
        if (what == null || what.isBlank()) {
            return;
        }
        if (!text.isEmpty()) {
            text.append('\n');
        }
        text.append(who).append(": ").append(what.strip());
    }

    private static List<Map<String, Object>> rows(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private static String cap(String json) {
        if (json == null) {
            return "";
        }
        return json.length() <= TOOL_JSON_CAP ? json : json.substring(0, TOOL_JSON_CAP) + "…[truncated]";
    }
}
