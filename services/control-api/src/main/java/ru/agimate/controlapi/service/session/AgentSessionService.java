package ru.agimate.controlapi.service.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.enums.AgentSessionScope;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
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
    private static final int TITLE_MAX_LENGTH = 80;

    private final AgentSessionRepository agentSessionRepository;

    public AgentSession getById(UUID id) {
        return agentSessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent session not found"));
    }

    public List<AgentSession> listByChannelId(UUID channelId) {
        return agentSessionRepository.findByChannelIdOrderByLastActivityAtDesc(channelId);
    }

    public List<AgentSession> listByChannelIds(List<UUID> channelIds) {
        return agentSessionRepository.findByChannelIdInOrderByLastActivityAtDesc(channelIds);
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
