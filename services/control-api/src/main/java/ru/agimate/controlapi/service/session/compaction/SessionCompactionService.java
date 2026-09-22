package ru.agimate.controlapi.service.session.compaction;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.LlmModelDefaults;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.LlmUsageLog;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.enums.SessionTitleSource;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.LlmModelDefaultsRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmUsageLogRepository;
import ru.agimate.controlapi.service.LlmUsageService;
import ru.agimate.controlapi.service.llm.ChatCompletionsHttp;
import ru.agimate.controlapi.service.llm.ExtraBodyMerge;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.runcontext.RunHistoryAssembler;
import ru.agimate.controlapi.service.session.AgentSessionService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Upkeep of a conversation (docs/decisions/context-compaction.md): a summary of its early runs, so
 * the history window keeps what the run limit would drop, and a title. One model call of purpose
 * ROUTINE made by control-api — no agent run, no worker, nothing in the session's queue partition.
 *
 * <p>{@link #due} is asked twice: by the planner when a run finishes, to decide whether a job is
 * worth scheduling, and by the job itself, which looks at the session afresh — between the two the
 * user may have renamed it or another job may have compacted it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCompactionService {

    /** The connector whose binding switches the upkeep on, and whose hidden tool is the job. */
    public static final String CONNECTOR_CODE = "sessions";
    public static final String COMPACT_JOB = "compact";

    /** Share of the model's context window the first call of a run may take before the next one is compacted. */
    static final double THRESHOLD = 0.85;

    /** Runs kept verbatim after a compaction — but never more than half of what is there. */
    static final int VERBATIM_TAIL_RUNS = 10;

    /** How far back a compaction looks for runs not yet retold. */
    static final int SCAN_RUNS = 200;

    /** The transcript handed to the routine model, in characters — roughly 50k tokens. */
    static final int SUMMARY_TRANSCRIPT_BUDGET = 200_000;

    /** A title needs the gist, not the record: the newest runs up to this many characters. */
    static final int TITLE_TRANSCRIPT_BUDGET = 20_000;
    static final int TITLE_RUNS = 3;

    static final String USAGE_CALL_PREFIX = "routine:";
    private static final Duration READ_TIMEOUT = Duration.ofMinutes(3);

    static final String SUMMARY_INSTRUCTIONS = """
            You keep the memory of a long conversation between a user and an AI agent. The early part \
            of it is about to leave the agent's context; retell it so the agent can carry on without it.

            Keep: who the user is and what they are after; decisions and agreements; facts, numbers, \
            names, ids and links that were established — verbatim; open tasks and promises; what the \
            agent did with which tools and what came of it. Drop small talk, repetition and dead ends \
            that led nowhere. Write in the language of the conversation, as plain prose or short lists. \
            If a previous summary is given, the result replaces it: merge, do not append.

            Also name the conversation: a short title (at most 60 characters, no quotes) for what it is \
            about now.

            Answer with a JSON object only: {"summary": "...", "title": "..."}""";

    static final String TITLE_INSTRUCTIONS = """
            Name this conversation between a user and an AI agent: a short title (at most 60 \
            characters, no quotes) for what it is about, in the language of the conversation.

            Answer with a JSON object only: {"title": "..."}""";

    /** What a session may need. */
    public enum Work {
        /** A generated title in place of the placeholder. */
        TITLE,
        /** A summary of the early runs, and a fresh title with it. */
        SUMMARY
    }

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;
    private final AgentRunTurnRepository turnRepository;
    private final LlmUsageLogRepository usageLogRepository;
    private final LlmProviderModelRepository providerModelRepository;
    private final LlmModelDefaultsRepository modelDefaultsRepository;
    private final LlmCredentialsResolver credentialsResolver;
    private final ChatCompletionsHttp http;
    private final LlmUsageService usageService;
    private final SessionCompactionWriter writer;

    /**
     * What the session needs now that {@code runId} has finished. Only a conversation with a person
     * is kept up: a trigger's session has no window to save, and a child's belongs to its parent.
     */
    public Set<Work> due(AgentSession session, UUID runId) {
        if (!kept(session)) {
            return Set.of();
        }
        Set<Work> due = EnumSet.noneOf(Work.class);
        if (session.getTitleSource() == null || session.getTitleSource() == SessionTitleSource.HINT) {
            due.add(Work.TITLE);
        }
        if (overThreshold(session, runId)) {
            due.add(Work.SUMMARY);
        }
        return due;
    }

    /**
     * The job: looks at the session afresh and does what is still due.
     *
     * @return what was done, for the job's log line
     */
    public Set<Work> maintain(UUID sessionId) {
        AgentSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return Set.of();
        }
        List<UUID> newestFirst = runRepository.findHistoryRunIds(sessionId, PageRequest.of(0, SCAN_RUNS));
        if (newestFirst.isEmpty()) {
            return Set.of();
        }
        Set<Work> due = due(session, newestFirst.get(0));
        Optional<AgentRunTurn> previous = turnRepository.findLatestSummary(sessionId);
        Optional<Retelling> retelling = due.contains(Work.SUMMARY)
                ? retelling(RunHistoryAssembler.sinceAnchor(newestFirst, previous))
                : Optional.empty();
        if (retelling.isEmpty() && !due.contains(Work.TITLE)) {
            return Set.of();
        }

        ResolvedLlm llm = credentialsResolver.resolveRoutine(session.getAgentId(), session.getUserId());
        Map<String, Object> answer;
        if (retelling.isPresent()) {
            answer = ask(llm, session, SUMMARY_INSTRUCTIONS,
                    summaryInput(previous, transcript(retelling.get().runs(), SUMMARY_TRANSCRIPT_BUDGET)));
        } else {
            answer = ask(llm, session, TITLE_INSTRUCTIONS,
                    transcript(newestFirst.subList(0, Math.min(TITLE_RUNS, newestFirst.size())).reversed(),
                            TITLE_TRANSCRIPT_BUDGET));
        }
        String summary = retelling.isPresent() ? text(answer, "summary", Integer.MAX_VALUE) : null;
        if (retelling.isPresent() && summary == null) {
            throw new IllegalStateException("the routine model returned no summary");
        }
        String title = text(answer, "title", AgentSessionService.TITLE_MAX_LENGTH);
        writer.write(sessionId, session.getAgentId(),
                retelling.map(Retelling::anchorRunId).orElse(null), summary, llm.model(), title);

        Set<Work> done = EnumSet.noneOf(Work.class);
        if (summary != null) {
            done.add(Work.SUMMARY);
        }
        if (title != null) {
            done.add(Work.TITLE);
        }
        return done;
    }

    static boolean kept(AgentSession session) {
        return session.getScope() == AgentSessionScope.CHANNEL
                && session.getParentSessionId() == null
                && session.getClosedAt() == null;
    }

    // ===== The threshold =====

    /**
     * The first model call of a run costs exactly the assembled context — system prompt, history
     * window, message; later calls carry this run's tool output, which the window will cap. A summary
     * written after the run started has already dealt with it.
     */
    private boolean overThreshold(AgentSession session, UUID runId) {
        AgentRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return false;
        }
        Optional<AgentRunTurn> summary = turnRepository.findLatestSummary(session.getId());
        if (summary.isPresent() && summary.get().getCreatedAt().isAfter(run.getCreatedAt())) {
            return false;
        }
        Optional<LlmUsageLog> first = turnRepository
                .findFirstByRunIdAndRoleAndTurnIndexGreaterThanEqualOrderByTurnIndexAsc(runId, AgentTurnRole.ASSISTANT, 0)
                .map(AgentRunTurn::getCallId)
                .flatMap(usageLogRepository::findByCallId);
        if (first.isEmpty()) {
            return false;
        }
        Integer window = contextWindow(first.get());
        return window != null && window > 0 && first.get().getInputTokens() > THRESHOLD * window;
    }

    /** The window of the model that answered: the provider's registry row, then the shared defaults. */
    private Integer contextWindow(LlmUsageLog call) {
        return providerModelRepository.findByLlmProviderIdAndModel(call.getLlmProviderId(), call.getModel())
                .map(LlmProviderModel::getContextWindow)
                .or(() -> modelDefaultsRepository.findByModelIn(List.of(call.getModel())).stream()
                        .findFirst()
                        .map(LlmModelDefaults::getContextWindow))
                .orElse(null);
    }

    // ===== What is retold =====

    /**
     * @param anchorRunId the oldest run kept verbatim — the summary sits on it
     * @param runs        the runs to retell, oldest first
     */
    record Retelling(UUID anchorRunId, List<UUID> runs) {
    }

    /**
     * The newest runs stay verbatim — {@link #VERBATIM_TAIL_RUNS}, but no more than half, or a few
     * heavy runs would leave nothing to compact; at least one, or the summary would have nowhere to
     * sit. The previous anchor is the oldest run here and is retold together with the rest.
     */
    static Optional<Retelling> retelling(List<UUID> sinceAnchorNewestFirst) {
        int size = sinceAnchorNewestFirst.size();
        int tail = Math.max(1, Math.min(VERBATIM_TAIL_RUNS, size / 2));
        if (size - tail < 1) {
            return Optional.empty();
        }
        List<UUID> retold = new ArrayList<>(sinceAnchorNewestFirst.subList(tail, size));
        return Optional.of(new Retelling(sinceAnchorNewestFirst.get(tail - 1), retold.reversed()));
    }

    /** @param runIds oldest first */
    private String transcript(List<UUID> runIds, int budget) {
        Map<UUID, List<AgentRunTurn>> byRun = new LinkedHashMap<>();
        runIds.forEach(id -> byRun.put(id, new ArrayList<>()));
        for (AgentRunTurn turn : turnRepository.findByRunIdInOrderByRunIdAscTurnIndexAsc(runIds)) {
            byRun.get(turn.getRunId()).add(turn);
        }
        return SessionTranscript.render(new ArrayList<>(byRun.values()), budget);
    }

    private static String summaryInput(Optional<AgentRunTurn> previous, String transcript) {
        return previous.map(p -> "Previous summary:\n" + p.getText() + "\n\nConversation since then:\n")
                .orElse("Conversation:\n") + transcript;
    }

    // ===== The call =====

    private Map<String, Object> ask(ResolvedLlm llm, AgentSession session, String instructions, String input) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("messages", List.of(
                Map.of("role", "system", "content", instructions),
                Map.of("role", "user", "content", input)));
        core.put("model", llm.model());
        Map<String, Object> response = http.post(llm.provider(), llm.apiKey(),
                ExtraBodyMerge.merge(llm.extraBody(), core), READ_TIMEOUT);
        recordUsage(llm, session, response);
        return parseAnswer(ChatCompletionsHttp.messageText(response));
    }

    private void recordUsage(ResolvedLlm llm, AgentSession session, Map<String, Object> response) {
        ChatCompletionsHttp.Usage usage = ChatCompletionsHttp.usage(response);
        if (usage == null) {
            log.warn("routine response carried no usage provider={} model={} — recording zeros",
                    llm.provider().getId(), llm.model());
            usage = new ChatCompletionsHttp.Usage(0, 0, null);
        }
        usageService.record(new LlmUsageService.UsageReport(
                USAGE_CALL_PREFIX + UUID.randomUUID(), null, session.getAgentId(), session.getUserId(),
                llm.provider().getId(), llm.model(),
                usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(), null));
    }

    /** The JSON object of the answer, tolerating a fence or a sentence around it. */
    static Map<String, Object> parseAnswer(String content) {
        int start = content == null ? -1 : content.indexOf('{');
        int end = content == null ? -1 : content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("the routine model answered without a JSON object");
        }
        JsonNode node = JsonUtils.toJsonNodeOrNull(content.substring(start, end + 1));
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("the routine model answered with unparsable JSON");
        }
        return JsonUtils.fromJsonToMap(node.toString());
    }

    /** A non-blank string field, one line for a title, cut to {@code max}; otherwise {@code null}. */
    static String text(Map<String, Object> answer, String field, int max) {
        if (!(answer.get(field) instanceof String value) || value.isBlank()) {
            return null;
        }
        String text = max == Integer.MAX_VALUE ? value.strip() : value.strip().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max);
    }
}
