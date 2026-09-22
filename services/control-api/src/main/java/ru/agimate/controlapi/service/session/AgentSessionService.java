package ru.agimate.controlapi.service.session;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.enums.SessionTitleSource;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.realtime.RealtimeEvent.SessionChanged;
import ru.agimate.controlapi.realtime.RealtimePublisher;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The one writer of {@code agent_sessions} and the one place that announces a session's change. Other
 * subsystems report what happened to a session through the methods here rather than writing the row
 * or publishing themselves.
 *
 * <p>The TTL heuristic below is a property of a dialogue, which ends, and not of a connection's
 * event stream, which does not — {@link #forConnection} ignores it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSessionService {

    public static final Duration SESSION_TTL = Duration.ofHours(12);
    public static final int TITLE_MAX_LENGTH = 80;
    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSessionRepository agentSessionRepository;
    private final WebchatMessageRepository webchatMessageRepository;
    private final RealtimePublisher realtime;

    public AgentSession getById(UUID id) {
        return agentSessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent session not found"));
    }

    /**
     * The owner's sessions, narrowed by whatever the caller named. The filter is on the session's own
     * {@code user_id} rather than on the channels behind it: a channel-shaped listing had to read the
     * user's channels first, and sessions of connection scope have no channel to be found through.
     */
    public Page<AgentSession> list(UUID userId, UUID agentId, UUID channelId, String connectorCode,
                                   LocalDateTime since, LocalDateTime until, int page, int size) {
        return list(userId, agentId, channelId, connectorCode, null, since, until, page, size);
    }

    /** The same, narrowed to the subagent sessions of one conversation when {@code parentSessionId} is set. */
    public Page<AgentSession> list(UUID userId, UUID agentId, UUID channelId, String connectorCode,
                                   UUID parentSessionId, LocalDateTime since, LocalDateTime until,
                                   int page, int size) {
        Specification<AgentSession> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (agentId != null) {
                predicates.add(cb.equal(root.get("agentId"), agentId));
            }
            if (channelId != null) {
                predicates.add(cb.equal(root.get("channelId"), channelId));
            }
            if (connectorCode != null) {
                predicates.add(cb.equal(root.get("connectorCode"), connectorCode));
            }
            if (parentSessionId != null) {
                predicates.add(cb.equal(root.get("parentSessionId"), parentSessionId));
            }
            // The activity window binds to the column the listing sorts by (lastActivityAt).
            if (since != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastActivityAt"), since));
            }
            if (until != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastActivityAt"), until));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return agentSessionRepository.findAll(spec, pageRequest(page, size));
    }

    /**
     * Freshest activity first, with the id as the tiebreak: a burst of sessions can share a
     * timestamp, and an unstable order lets a page repeat one row while skipping another. The id is
     * a uuidv7, so it breaks the tie in the same direction time does.
     */
    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "lastActivityAt").and(Sort.by(Sort.Direction.DESC, "id")));
    }

    @Transactional
    public AgentSession findOrCreateActive(Channel channel, String firstMessageHint) {
        return findActive(channel).orElseGet(() -> createNew(channel, firstMessageHint));
    }

    /** The live session, if the channel has one — for callers that must not conjure one (the stop command). */
    public Optional<AgentSession> findActive(Channel channel) {
        List<AgentSession> active = agentSessionRepository.findActive(
                channel.getId(), LocalDateTime.now().minus(SESSION_TTL));
        return active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }

    /** Always a new session, bypassing the TTL heuristic — for channels that choose the session explicitly (webchat). */
    @Transactional
    public AgentSession createNew(Channel channel, String firstMessageHint) {
        AgentSession session = AgentSession.builder()
                .scope(AgentSessionScope.CHANNEL)
                .agentId(channel.getAgentId())
                .userId(channel.getUserId())
                .connectorCode(channel.getConnectorCode())
                .connectionId(channel.getConnectionId())
                .channelId(channel.getId())
                .title(buildTitle(firstMessageHint))
                .titleSource(hintSource(firstMessageHint))
                .lastActivityAt(LocalDateTime.now())
                .build();
        AgentSession saved = agentSessionRepository.save(session);
        log.info("Created new channel session id={} for channel id={}", saved.getId(), channel.getId());
        realtime.publish(SessionChanged.created(saved.getId()));
        return saved;
    }

    /**
     * The live session of a connection, created if there is none: one per {@code (agent, connection)},
     * so the events of one connection share a writer and a queue partition
     * (docs/decisions/agent-sessions.md). Its own transaction: routing itself runs outside one, and the
     * unique index must not be held while the run is being enqueued into DBOS.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID forConnection(UUID agentId, UUID userId, String connectorCode, UUID connectionId) {
        LocalDateTime now = LocalDateTime.now();
        AgentSession live = agentSessionRepository.findLiveConnectionSession(agentId, connectionId)
                .orElse(null);
        if (live != null) {
            agentSessionRepository.touch(live.getId(), now);
            return live.getId();
        }
        int created = agentSessionRepository.insertConnectionSession(
                agentId, userId, connectorCode, connectionId, now);
        AgentSession session = agentSessionRepository.findLiveConnectionSession(agentId, connectionId)
                .orElseThrow(() -> new IllegalStateException(
                        "Connection session vanished right after insert: agent=" + agentId
                                + " connection=" + connectionId));
        // Only the call that inserted announces it; the loser of a race reads the winner's row.
        if (created > 0) {
            log.info("Created connection session id={} for agent {} connection {}",
                    session.getId(), agentId, connectionId);
            realtime.publish(SessionChanged.created(session.getId()));
        }
        return session.getId();
    }

    /** A subagent's session: a new conversation of the subagents channel that works for {@code parentSessionId}. */
    @Transactional
    public AgentSession createChild(Channel channel, UUID parentSessionId, String title) {
        AgentSession session = AgentSession.builder()
                .scope(AgentSessionScope.CHANNEL)
                .agentId(channel.getAgentId())
                .userId(channel.getUserId())
                .connectorCode(channel.getConnectorCode())
                .connectionId(channel.getConnectionId())
                .channelId(channel.getId())
                .parentSessionId(parentSessionId)
                .title(buildTitle(title))
                .titleSource(hintSource(title))
                .lastActivityAt(LocalDateTime.now())
                .build();
        AgentSession saved = agentSessionRepository.save(session);
        log.info("Created subagent session id={} for conversation {}", saved.getId(), parentSessionId);
        realtime.publish(SessionChanged.created(saved.getId()));
        return saved;
    }

    /**
     * An open session of this channel; empty when the session belongs to something else or is closed.
     * The channel comparison is null-safe on purpose — a session of another scope has no channel at
     * all, and reaching here with its id must not be an NPE.
     */
    public Optional<AgentSession> findOpen(UUID sessionId, UUID channelId) {
        return agentSessionRepository.findById(sessionId)
                .filter(s -> Objects.equals(s.getChannelId(), channelId))
                .filter(s -> s.getClosedAt() == null);
    }

    /** Rename explicitly; unlike the title derived from a message, an over-long one is refused, not cut. */
    @Transactional
    public AgentSession rename(AgentSession session, String title) {
        String trimmed = title.strip();
        if (trimmed.isEmpty()) {
            throw new BadRequestStatusException("Title must not be blank");
        }
        if (trimmed.length() > TITLE_MAX_LENGTH) {
            throw new BadRequestStatusException("Title is longer than " + TITLE_MAX_LENGTH + " characters");
        }
        session.setTitle(trimmed);
        session.setTitleSource(SessionTitleSource.USER);
        AgentSession saved = agentSessionRepository.save(session);
        realtime.publish(SessionChanged.updated(saved.getId()));
        return saved;
    }

    /** A title from the compaction job; a title the user gave is never written over, and then nothing changed. */
    @Transactional
    public void writeGeneratedTitle(UUID sessionId, String title) {
        if (agentSessionRepository.writeGeneratedTitle(sessionId, title, LocalDateTime.now()) > 0) {
            realtime.publish(SessionChanged.updated(sessionId));
        }
    }

    /** Set the title from the first message, if it is still empty. */
    @Transactional
    public void setTitleIfEmpty(AgentSession session, String hint) {
        if (session.getTitle() == null && hint != null && !hint.isBlank()) {
            session.setTitle(buildTitle(hint));
            session.setTitleSource(SessionTitleSource.HINT);
            agentSessionRepository.save(session);
            realtime.publish(SessionChanged.updated(session.getId()));
        }
    }

    @Transactional
    public void bumpLastActivityAt(AgentSession session) {
        session.setLastActivityAt(LocalDateTime.now());
        agentSessionRepository.save(session);
    }

    /**
     * Activity of a session that is not loaded. Not announced: the activity of a connection's session
     * moves with every trigger, and an event per trigger is noise.
     */
    @Transactional
    public void touch(UUID sessionId) {
        agentSessionRepository.touch(sessionId, LocalDateTime.now());
    }

    /** A message landed in the session's UI log: its preview and unread count moved. */
    public void messageRecorded(UUID sessionId) {
        realtime.publish(SessionChanged.updated(sessionId));
    }

    /** A run of the session started or finished: «working now» in the listing moved. */
    public void runStateChanged(UUID sessionId) {
        realtime.publish(SessionChanged.updated(sessionId));
    }

    /**
     * Read up to {@code lastReadMessageId}, or up to the end of the conversation when none is named.
     * The pointer only ever moves forward.
     *
     * <p>The pointer addresses {@code webchat_messages} by definition — it is «how far the user has
     * scrolled», and the user reads in the web UI. A session of another connector simply has no rows
     * there, and marking it read is a no-op rather than an error.
     */
    @Transactional
    public void markRead(UUID sessionId, UUID lastReadMessageId) {
        UUID pointer = lastReadMessageId;
        if (pointer != null) {
            // A pointer from another session — or invented — would silence this session's badge forever.
            if (!webchatMessageRepository.existsByIdAndSessionId(pointer, sessionId)) {
                throw new BadRequestStatusException("Message does not belong to this session");
            }
        } else {
            pointer = webchatMessageRepository.findLastMessageId(sessionId).orElse(null);
        }
        if (pointer != null) {
            advanceReadPointer(sessionId, pointer);
        }
    }

    /** Whatever stood in the conversation up to now has been read. */
    @Transactional
    public void markReadThroughLatest(UUID sessionId) {
        webchatMessageRepository.findLastMessageId(sessionId)
                .ifPresent(id -> advanceReadPointer(sessionId, id));
    }

    /**
     * Move the session's read pointer to {@code messageId}. Never rewinds — see
     * {@code AgentSessionRepository.advanceReadPointer}. Only a pointer that moved is announced:
     * reopening a read chat would otherwise send an event every time.
     */
    @Transactional
    public void advanceReadPointer(UUID sessionId, UUID messageId) {
        if (agentSessionRepository.advanceReadPointer(sessionId, messageId, LocalDateTime.now()) > 0) {
            realtime.publish(SessionChanged.updated(sessionId));
        }
    }

    @Transactional
    public AgentSession close(UUID id) {
        AgentSession session = getById(id);
        if (session.getClosedAt() == null) {
            session.setClosedAt(LocalDateTime.now());
            agentSessionRepository.save(session);
            realtime.publish(SessionChanged.updated(id));
        }
        return session;
    }

    private static SessionTitleSource hintSource(String hint) {
        return hint == null || hint.isBlank() ? null : SessionTitleSource.HINT;
    }

    private String buildTitle(String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        String trimmed = hint.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH);
    }
}
