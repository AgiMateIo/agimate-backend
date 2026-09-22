package ru.agimate.controlapi.connectors.internal.sessions;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.connectors.core.PromptBlockProvider;
import ru.agimate.controlapi.connectors.core.dto.PromptBlock;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.service.channel.handler.PromptEscaping;
import ru.agimate.controlapi.service.session.compaction.SessionCompactionService;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Facade of the {@code sessions} connector (docs/decisions/agent-sessions-connector.md): the agent
 * sees its other live conversations and searches everything said in past ones, and its long
 * conversations are compacted and titled. A mode row per user, bound through the {@code sessions}
 * skill; the binding is also the switch of the upkeep — without it the window is cut as before.
 *
 * <p>Owner of the data: the agent ({@code env.agentId}) — its sessions, its turn ledger.
 */
@Component
public class SessionsConnectorService extends BaseConnectorHandler
        implements InternalConnectorHandler, PromptBlockProvider {

    public static final String CONNECTOR_CODE = SessionCompactionService.CONNECTOR_CODE;

    static final String SESSIONS_BLOCK = "sessions";
    /** A conversation quiet for longer than this is not «going on» any more. */
    static final Duration LIVE_WINDOW = Duration.ofMinutes(30);
    static final int MAX_LISTED = 10;
    private static final UUID NO_SESSION = new UUID(0, 0);

    private final AgentSessionRepository sessionRepository;
    private final AgentRunRepository runRepository;

    public SessionsConnectorService(SessionsToolService toolService, AgentSessionRepository sessionRepository,
                                    AgentRunRepository runRepository) {
        super(toolService);
        this.sessionRepository = sessionRepository;
        this.runRepository = runRepository;
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Sessions";
    }

    @Override
    public String connectorDescription() {
        return "The agent sees its other conversations going on right now and searches everything said "
                + "in past ones; long conversations are compacted into a summary and titled automatically.";
    }

    /**
     * The agent's other live conversations, in the user turn: stale by the next run, so never
     * persisted, and nothing when there are none. A child's run gets nothing — it works for one
     * conversation and has no business with the others.
     */
    @Override
    public List<PromptBlock> promptBlocks(ConnectorEnv env) {
        if (env.agentId() == null) {
            return List.of();
        }
        UUID current = env.sessionId();
        if (current != null && sessionRepository.findById(current)
                .map(s -> s.getParentSessionId() != null).orElse(false)) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<AgentSession> live = sessionRepository.findLiveSiblings(env.agentId(),
                current == null ? NO_SESSION : current, now.minus(LIVE_WINDOW), PageRequest.of(0, MAX_LISTED));
        if (live.isEmpty()) {
            return List.of();
        }
        Set<UUID> running = new HashSet<>(runRepository.findSessionsWithRunningRuns(
                live.stream().map(AgentSession::getId).toList()));
        String lines = live.stream()
                .map(s -> line(s, running.contains(s.getId()), now))
                .collect(Collectors.joining("\n"));
        return List.of(PromptBlock.user(SESSIONS_BLOCK,
                "Your other conversations going on right now (for reference, not a task):\n" + lines));
    }

    /** The title is the model's or the user's words: one line, no tags. */
    static String line(AgentSession session, boolean running, LocalDateTime now) {
        String title = session.getTitle() == null ? "untitled" : PromptEscaping.attribute(session.getTitle());
        String state = running ? "answering now"
                : "quiet for " + Math.max(1, Duration.between(session.getLastActivityAt(), now).toMinutes()) + " min";
        return "- «" + title + "» · " + session.getConnectorCode() + " · " + state;
    }
}
