package ru.agimate.controlapi.connectors.internal.sessions;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.session.compaction.SessionCompactionService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tools of the {@code sessions} connector. The owner of the data is the agent: the search reads the
 * turn ledger of {@code env.agentId}, every session of it and nothing else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionsToolService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 50;
    /** Characters of context on each side of the match. */
    static final int FRAGMENT_RADIUS = 200;

    private final AgentRunTurnRepository turnRepository;
    private final AgentSessionRepository sessionRepository;
    private final SessionCompactionService compactionService;

    @Tool(name = "search_messages",
            description = "Search what was said in all your conversations — every channel, including parts "
                    + "that have long left your context. Matches a word or phrase as a substring, case-"
                    + "insensitively, not by meaning. Returns fragments with the conversation's title and "
                    + "time, newest first. For facts you chose to remember use your memory instead.",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public Map<String, Object> searchMessages(
            @ToolParam("A word or phrase that was likely said, e.g. \"supplier\" or \"invoice 4417\"") String query,
            @ToolParam(value = "How many fragments to return (default 20, at most 50)", required = false)
            Integer limit) {
        ConnectorEnv env = ConnectorEnvHolder.current();
        if (env.agentId() == null) {
            throw new ConnectorException("sessions.search_messages must be called by an agent");
        }
        String needle = query == null ? "" : query.strip();
        if (needle.isEmpty()) {
            throw new ConnectorException("query must not be blank");
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<AgentRunTurn> turns = turnRepository.searchText(env.agentId(), "%" + escapeLike(needle) + "%", size);

        Set<UUID> sessionIds = turns.stream().map(AgentRunTurn::getSessionId).collect(Collectors.toSet());
        Map<UUID, AgentSession> sessions = sessionRepository.findAllById(sessionIds).stream()
                .collect(Collectors.toMap(AgentSession::getId, Function.identity()));
        List<Map<String, Object>> found = new ArrayList<>(turns.size());
        for (AgentRunTurn turn : turns) {
            AgentSession session = sessions.get(turn.getSessionId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", turn.getSessionId().toString());
            item.put("title", session == null ? null : session.getTitle());
            item.put("current", turn.getSessionId().equals(env.sessionId()));
            item.put("at", turn.getCreatedAt().toString());
            item.put("role", turn.getRole() == AgentTurnRole.USER ? "user" : "agent");
            item.put("fragment", fragment(turn.getText(), needle));
            found.add(item);
        }
        return Map.of("messages", found);
    }

    /**
     * Hidden dispatch target of the job the planner schedules when a conversation's run finishes
     * (docs/decisions/context-compaction.md). Never fails the job: a failure is logged and the next
     * finished run plans again, where a ONETIME retry would spend a model call on every lap.
     */
    @Tool(name = SessionCompactionService.COMPACT_JOB,
            description = "Internal: compact a long conversation and title it", visibility = {})
    public Map<String, Object> compact() {
        UUID sessionId = ConnectorEnvHolder.current().sessionId();
        if (sessionId == null) {
            throw new ConnectorException("sessions.compact needs a session");
        }
        try {
            Set<SessionCompactionService.Work> done = compactionService.maintain(sessionId);
            log.info("session {} upkeep done: {}", sessionId, done);
            return Map.of("done", done.stream().map(Enum::name).toList());
        } catch (Exception e) {
            log.warn("session {} upkeep failed: {}", sessionId, e.getMessage(), e);
            return Map.of("done", List.of(), "error", String.valueOf(e.getMessage()));
        }
    }

    /** The text around the first match; the whole text when it is short. */
    static String fragment(String text, String needle) {
        if (text.length() <= 2 * FRAGMENT_RADIUS) {
            return text;
        }
        int at = text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        int from = Math.max(0, (at < 0 ? 0 : at) - FRAGMENT_RADIUS);
        int to = Math.min(text.length(), (at < 0 ? 0 : at) + needle.length() + FRAGMENT_RADIUS);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    /** ILIKE wildcards in the query are literal characters; the backslash is the escape. */
    static String escapeLike(String query) {
        return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
