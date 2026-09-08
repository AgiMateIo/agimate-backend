package ru.agimate.controlapi.service.runcontext;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.service.dto.ToolTurnRecord;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
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
 *
 * <p>The window is also where progressive disclosure keeps its state: every tool the window called
 * or had described is reported back ({@link RunHistory#disclosedTools}), and a {@code load_skill}
 * body ({@link ToolTurnRecord.Material#SKILL}) stays whole in the transcript instead of being cut to
 * the tool-result cap — the model reads the instruction where it loaded it, in conversation order.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunHistoryAssembler {

    /** Cap on a single JSON of a tool turn (arguments or result) in the context — budget beats completeness. */
    static final int TOOL_JSON_CONTEXT_CAP = 4 * 1024;

    /** A skill body kept whole in the transcript is still bounded — a 17 KB platform skill fits, a runaway one does not. */
    static final int SKILL_BODY_CONTEXT_CAP = 32 * 1024;

    /**
     * Bytes of disclosed material the prefix may carry over from the window: skill bodies here,
     * tool schemas in the context assembly. Newest first; what does not fit is treated as never
     * disclosed, which is what the window's own eviction would do a few runs later.
     */
    static final int DISCLOSED_BUDGET_BYTES = 96 * 1024;

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
    public RunHistory assemble(UUID sessionId, int limit, Set<ContextSpec.HistoryPart> parts) {
        if (sessionId == null || limit <= 0) {
            return RunHistory.empty();
        }
        List<UUID> newestFirst = agentRunRepository.findHistoryRunIds(sessionId, PageRequest.of(0, limit));
        if (newestFirst.isEmpty()) {
            return RunHistory.empty();
        }
        // The window is taken from the newest end, the transcript reads from the oldest.
        List<UUID> runIds = new ArrayList<>(newestFirst);
        Collections.reverse(runIds);
        Map<UUID, List<AgentRunTurn>> byRun = groupByRun(runIds);

        List<AgentRunTurn> turns = new ArrayList<>();
        for (UUID runId : withinTurnBudget(runIds, byRun)) {
            turns.addAll(byRun.getOrDefault(runId, List.of()));
        }
        boolean tools = parts.contains(ContextSpec.HistoryPart.TOOLS);
        KeptBodies kept = tools ? keptBodies(turns) : new KeptBodies(Set.of(), DISCLOSED_BUDGET_BYTES);

        List<RunHistoryMessage> history = new ArrayList<>();
        for (AgentRunTurn turn : turns) {
            RunHistoryMessage mapped = toHistoryMessage(turn, parts, kept.results());
            if (mapped != null) {
                history.add(mapped);
            }
        }
        return new RunHistory(history, tools ? disclosedTools(turns) : List.of(), kept.budgetLeft());
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

    // ===== Disclosure read off the window =====

    /**
     * Every tool the window called or had described, newest first. The name is the LLM-facing one
     * the ledger stores — the same string control-api minted into {@code llm_name}, so the context
     * assembly matches it against the run's catalogue without taking it apart.
     */
    private static List<String> disclosedTools(List<AgentRunTurn> turns) {
        Map<String, Integer> lastSeen = new LinkedHashMap<>();
        int position = 0;
        for (AgentRunTurn turn : turns) {
            if (turn.getRole() == AgentTurnRole.ASSISTANT && !isEmpty(turn.getToolCalls())) {
                for (Map<String, Object> row : turn.getToolCalls()) {
                    lastSeen.put(ToolTurnRecord.Call.fromRow(row).name(), position++);
                }
            }
            if (turn.getRole() == AgentTurnRole.TOOL && !isEmpty(turn.getToolResults())) {
                for (Map<String, Object> row : turn.getToolResults()) {
                    ToolTurnRecord.Result result = ToolTurnRecord.Result.fromRow(row);
                    if (result.material() == ToolTurnRecord.Material.TOOLS) {
                        for (String name : names(result.outputJson(), "disclosed")) {
                            lastSeen.put(name, position++);
                        }
                    }
                }
            }
        }
        return lastSeen.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Which {@code load_skill} results stay whole, and what is left of the budget after them. */
    private record KeptBodies(Set<String> results, int budgetLeft) {}

    /**
     * The newest {@code load_skill} result per skill is kept whole, newest first while the budget
     * lasts; an older load of the same skill is capped like any result, so a reload does not stack
     * copies of the body. A result naming several skills counts once, by its first unseen skill.
     */
    private static KeptBodies keptBodies(List<AgentRunTurn> turns) {
        Set<String> kept = new HashSet<>();
        Set<String> seenSkills = new HashSet<>();
        int budget = DISCLOSED_BUDGET_BYTES;
        for (int i = turns.size() - 1; i >= 0; i--) {
            AgentRunTurn turn = turns.get(i);
            if (turn.getRole() != AgentTurnRole.TOOL || isEmpty(turn.getToolResults())) {
                continue;
            }
            for (Map<String, Object> row : turn.getToolResults()) {
                ToolTurnRecord.Result result = ToolTurnRecord.Result.fromRow(row);
                if (result.material() != ToolTurnRecord.Material.SKILL || result.failed()) {
                    continue;
                }
                List<String> skills = names(result.outputJson(), "skills");
                if (skills.isEmpty() || seenSkills.containsAll(skills)) {
                    continue;
                }
                int size = Math.min(bytes(result.outputJson()), SKILL_BODY_CONTEXT_CAP);
                if (size > budget) {
                    continue; // evicted by age: an older body does not push a newer one out
                }
                budget -= size;
                seenSkills.addAll(skills);
                kept.add(resultKey(turn, result));
            }
        }
        return new KeptBodies(kept, budget);
    }

    /** Names under {@code key}: a JSON array of strings, or of objects carrying {@code name}. */
    private static List<String> names(String outputJson, String key) {
        JsonNode root = outputJson == null ? null : JsonUtils.toJsonNodeOrNull(outputJson);
        if (root == null || !root.path(key).isArray()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (JsonNode node : root.get(key)) {
            String name = node.isTextual() ? node.asText() : node.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Call ids are minted per run, so the run scopes them. */
    private static String resultKey(AgentRunTurn turn, ToolTurnRecord.Result result) {
        return turn.getRunId() + ":" + result.id();
    }

    private static int bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    // ===== Turns as messages =====

    /**
     * A turn as a history record. {@code kind} survives only because the worker reads it to tell the
     * user's turn from everything else; the structure travels in {@code toolTurn}.
     * {@code thinking_text} is never selected — see {@link ContextSpec.HistoryPart#REASONING}.
     */
    private static RunHistoryMessage toHistoryMessage(AgentRunTurn turn, Set<ContextSpec.HistoryPart> parts,
                                                      Set<String> keptBodies) {
        boolean dialog = parts.contains(ContextSpec.HistoryPart.DIALOG);
        boolean tools = parts.contains(ContextSpec.HistoryPart.TOOLS);
        return switch (turn.getRole()) {
            case USER -> dialog && hasText(turn)
                    ? new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, turn.getText())
                    : null;
            case ASSISTANT -> assistantMessage(turn, dialog, tools);
            case TOOL -> tools && !isEmpty(turn.getToolResults())
                    ? new RunHistoryMessage(ChannelSessionMessageKind.PROGRESS, "",
                            new ToolTurnRecord(null, List.of(), results(turn, keptBodies)))
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
                .map(c -> new ToolTurnRecord.Call(c.id(), c.name(), cap(c.argumentsJson(), TOOL_JSON_CONTEXT_CAP)))
                .toList();
    }

    private static List<ToolTurnRecord.Result> results(AgentRunTurn turn, Set<String> keptBodies) {
        return turn.getToolResults().stream()
                .map(ToolTurnRecord.Result::fromRow)
                .map(r -> new ToolTurnRecord.Result(r.id(), r.name(),
                        cap(r.outputJson(), keptBodies.contains(resultKey(turn, r)) ? SKILL_BODY_CONTEXT_CAP : TOOL_JSON_CONTEXT_CAP),
                        r.failed(), r.material()))
                .toList();
    }

    private static boolean hasText(AgentRunTurn turn) {
        return turn.getText() != null && !turn.getText().isBlank();
    }

    private static boolean isEmpty(List<Map<String, Object>> records) {
        return records == null || records.isEmpty();
    }

    /** Truncation down to the context budget: the ledger is uncapped on purpose, so the reader caps. */
    private static String cap(String json, int limit) {
        if (json == null || json.length() <= limit) {
            return json;
        }
        return json.substring(0, limit) + "…[truncated]";
    }
}
