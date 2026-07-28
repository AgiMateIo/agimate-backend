package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.repositories.ChannelSessionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChannelSessionService {

    public static final Duration SESSION_TTL = Duration.ofHours(12);
    private static final int TITLE_MAX_LENGTH = 80;

    private final ChannelSessionRepository channelSessionRepository;

    public ChannelSession getById(UUID id) {
        return channelSessionRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
    }

    public List<ChannelSession> listByChannelId(UUID channelId) {
        return channelSessionRepository.findByChannelIdOrderByLastMessageAtDesc(channelId);
    }

    public List<ChannelSession> listByChannelIds(List<UUID> channelIds) {
        return channelSessionRepository.findByChannelIdInOrderByLastMessageAtDesc(channelIds);
    }

    @Transactional
    public ChannelSession findOrCreateActive(Channel channel, String firstMessageHint) {
        LocalDateTime threshold = LocalDateTime.now().minus(SESSION_TTL);
        List<ChannelSession> active = channelSessionRepository.findActive(channel.getId(), threshold);
        if (!active.isEmpty()) {
            return active.get(0);
        }
        return createNew(channel, firstMessageHint);
    }

    /** Always a new session, bypassing the TTL heuristic — for channels that choose the session explicitly (webchat). */
    @Transactional
    public ChannelSession createNew(Channel channel, String firstMessageHint) {
        ChannelSession session = ChannelSession.builder()
                .channelId(channel.getId())
                .title(buildTitle(firstMessageHint))
                .lastMessageAt(LocalDateTime.now())
                .build();
        ChannelSession saved = channelSessionRepository.save(session);
        log.info("Created new channel session id={} for channel id={}", saved.getId(), channel.getId());
        return saved;
    }

    /** An open (not closed) session of this channel; empty when the channel is someone else's or the session is closed. */
    public Optional<ChannelSession> findOpen(UUID sessionId, UUID channelId) {
        return channelSessionRepository.findById(sessionId)
                .filter(s -> s.getChannelId().equals(channelId))
                .filter(s -> s.getClosedAt() == null);
    }

    /** Set the title from the first message, if it is still empty. */
    @Transactional
    public void setTitleIfEmpty(ChannelSession session, String hint) {
        if (session.getTitle() == null && hint != null && !hint.isBlank()) {
            session.setTitle(buildTitle(hint));
            channelSessionRepository.save(session);
        }
    }

    @Transactional
    public void bumpLastMessageAt(ChannelSession session) {
        session.setLastMessageAt(LocalDateTime.now());
        channelSessionRepository.save(session);
    }

    @Transactional
    public ChannelSession close(UUID id) {
        ChannelSession session = getById(id);
        if (session.getClosedAt() == null) {
            session.setClosedAt(LocalDateTime.now());
            channelSessionRepository.save(session);
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
