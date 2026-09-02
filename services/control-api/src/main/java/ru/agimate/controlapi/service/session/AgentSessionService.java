package ru.agimate.controlapi.service.session;

import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Channel sessions: the conversation a user is having with an agent. Sessions of other scopes are
 * resolved elsewhere — the TTL heuristic below is a property of a dialogue, which ends, and not of a
 * connection's event stream, which does not.
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
                .lastActivityAt(LocalDateTime.now())
                .build();
        AgentSession saved = agentSessionRepository.save(session);
        log.info("Created new channel session id={} for channel id={}", saved.getId(), channel.getId());
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
        return agentSessionRepository.save(session);
    }

    /** Set the title from the first message, if it is still empty. */
    @Transactional
    public void setTitleIfEmpty(AgentSession session, String hint) {
        if (session.getTitle() == null && hint != null && !hint.isBlank()) {
            session.setTitle(buildTitle(hint));
            agentSessionRepository.save(session);
        }
    }

    @Transactional
    public void bumpLastActivityAt(AgentSession session) {
        session.setLastActivityAt(LocalDateTime.now());
        agentSessionRepository.save(session);
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
     * {@code AgentSessionRepository.advanceReadPointer}.
     */
    @Transactional
    public void advanceReadPointer(UUID sessionId, UUID messageId) {
        agentSessionRepository.advanceReadPointer(sessionId, messageId, LocalDateTime.now());
    }

    @Transactional
    public AgentSession close(UUID id) {
        AgentSession session = getById(id);
        if (session.getClosedAt() == null) {
            session.setClosedAt(LocalDateTime.now());
            agentSessionRepository.save(session);
        }
        return session;
    }

    private String buildTitle(String hint) {
        if (hint == null || hint.isBlank()) {
            return null;
        }
        String trimmed = hint.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH);
    }
}
