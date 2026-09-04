package ru.agimate.controlapi.service.runcontext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The history of a session for the next run's context, assembled from the canonical turn ledger
 * ({@code agent_run_turns}) rather than from the channel projection. The ledger is the model's own
 * message list; the projection is what the user is shown, and reading history off it meant restoring
 * structure from a rendering — parsing tool turns back out of {@code message_json} and dropping rows
 * whose structure could not be recovered.
 *
 * <p>Nothing is validated here. A run's ledger is checked once, when the run finishes
 * ({@code AgentRunTurnService.isLedgerIntact} → {@code agent_runs.turns_intact}), and the window
 * query simply skips the runs that failed — assembly stays a read.
 *
 * <p>The wire format is unchanged: an assistant turn that called tools goes out as the calls record
 * and the following tool turn as the results record, which is exactly the adjacency the worker
 * already stitches into a native {@code tool_use}/{@code tool_result} pair.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunHistoryAssembler {

    /** Cap on a single JSON of a tool turn (arguments or result) in the context — budget beats completeness. */
    static final int TOOL_JSON_CONTEXT_CAP = 4 * 1024;

    /**
     * Backstop on the whole window. The window is counted in runs, and a run heavy on tools is worth
     * dozens of turns — twenty such runs would blow the context on their own. Runs are dropped whole,
     * oldest first: half a run in history is worse than none, because the model reads the tail as the
     * whole story.
     */
    static final int MAX_HISTORY_TURNS = 300;

    private final AgentRunRepository agentRunRepository;
    private final AgentRunTurnRepository turnRepository;

    /**
     * @param limit window in runs; {@code 0} — no history at all
     * @param parts which parts of a past run to carry over
     */
    public List<RunHistoryMessage> assemble(UUID sessionId, int limit, Set<ContextSpec.HistoryPart> parts) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        List<UUID> newestFirst = agentRunRepository.findHistoryRunIds(sessionId, PageRequest.of(0, limit));
        if (newestFirst.isEmpty()) {
            return List.of();
        }
        // The window is taken from the newest end, the transcript reads from the oldest.
        List<UUID> runIds = new ArrayList<>(newestFirst);
        Collections.reverse(runIds);
        Map<UUID, List<AgentRunTurn>> byRun = groupByRun(runIds);

        List<RunHistoryMessage> history = new ArrayList<>();
        for (UUID runId : withinTurnBudget(runIds, byRun)) {
            for (AgentRunTurn turn : byRun.getOrDefault(runId, List.of())) {
                RunHistoryMessage mapped = toHistoryMessage(turn, parts);
                if (mapped != null) {
                    history.add(mapped);
                }
            }
        }
        return history;
    }

    private Map<UUID, List<AgentRunTurn>> groupByRun(List<UUID> runIds) {
        Map<UUID, List<AgentRunTurn>> byRun = new LinkedHashMap<>();
        for (AgentRunTurn turn : turnRepository.findByRunIdInOrderByRunIdAscTurnIndexAsc(runIds)) {
            byRun.computeIfAbsent(turn.getRunId(), id -> new ArrayList<>()).add(turn);
        }
        return byRun;
    }

    /** The tail of the window that fits {@value #MAX_HISTORY_TURNS} turns, in chronological order. */
    private static List<UUID> withinTurnBudget(List<UUID> runIds, Map<UUID, List<AgentRunTurn>> byRun) {
        List<UUID> kept = new ArrayList<>(runIds.size());
        int turns = 0;
        for (int i = runIds.size() - 1; i >= 0; i--) {
            int size = byRun.getOrDefault(runIds.get(i), List.of()).size();
            if (turns + size > MAX_HISTORY_TURNS && !kept.isEmpty()) {
                log.debug("history window trimmed to {} of {} runs by the turn budget", kept.size(), runIds.size());
                break;
            }
            turns += size;
            kept.add(runIds.get(i));
        }
        Collections.reverse(kept);
        return kept;
    }

    /**
     * A turn as a history record. {@code kind} survives only because the worker reads it to tell the
     * user's turn from everything else; the structure travels in {@code toolTurn}.
     * {@code thinking_text} is never selected — see {@link ContextSpec.HistoryPart#REASONING}.
     */
    private static RunHistoryMessage toHistoryMessage(AgentRunTurn turn, Set<ContextSpec.HistoryPart> parts) {
        boolean dialog = parts.contains(ContextSpec.HistoryPart.DIALOG);
        boolean tools = parts.contains(ContextSpec.HistoryPart.TOOLS);
        return switch (turn.getRole()) {
            case USER -> dialog && hasText(turn)
                    ? new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, turn.getText())
                    : null;
            case ASSISTANT -> assistantMessage(turn, dialog, tools);
            case TOOL -> tools && !isEmpty(turn.getToolResults())
                    ? new RunHistoryMessage(ChannelSessionMessageKind.PROGRESS, "",
                            new ToolTurnRecord(null, List.of(), results(turn)))
                    : null;
            case SYSTEM -> null; // never written to the ledger, but the role exists in the enum
        };
    }

    /**
     * An assistant turn is one of two things. With tool calls it is the calls half of a tool turn —
     * without {@code TOOLS} it is dropped whole, together with its preamble, so that no dangling
     * results record is left behind. Without calls it is the answer.
     */
    private static RunHistoryMessage assistantMessage(AgentRunTurn turn, boolean dialog, boolean tools) {
        if (!isEmpty(turn.getToolCalls())) {
            return tools
                    ? new RunHistoryMessage(ChannelSessionMessageKind.PROGRESS, turn.getText(),
                            new ToolTurnRecord(turn.getText(), calls(turn), List.of()))
                    : null;
        }
        return dialog && hasText(turn)
                ? new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, turn.getText())
                : null;
    }

    private static List<ToolTurnRecord.Call> calls(AgentRunTurn turn) {
        return turn.getToolCalls().stream()
                .map(ToolTurnRecord.Call::fromRow)
                .map(c -> new ToolTurnRecord.Call(c.id(), c.name(), cap(c.argumentsJson())))
                .toList();
    }

    private static List<ToolTurnRecord.Result> results(AgentRunTurn turn) {
        return turn.getToolResults().stream()
                .map(ToolTurnRecord.Result::fromRow)
                .map(r -> new ToolTurnRecord.Result(r.id(), r.name(), cap(r.outputJson()), r.failed()))
                .toList();
    }

    private static boolean hasText(AgentRunTurn turn) {
        return turn.getText() != null && !turn.getText().isBlank();
    }

    private static boolean isEmpty(List<Map<String, Object>> records) {
        return records == null || records.isEmpty();
    }

    /** Truncation down to the context budget: the ledger is uncapped on purpose, so the reader caps. */
    private static String cap(String json) {
        if (json == null || json.length() <= TOOL_JSON_CONTEXT_CAP) {
            return json;
        }
        return json.substring(0, TOOL_JSON_CONTEXT_CAP) + "…[truncated]";
    }
}
