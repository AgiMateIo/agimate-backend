package ru.agimate.deviceapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.ChannelSession;
import ru.agimate.deviceapi.database.repositories.ChannelSessionRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
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

    @Transactional
    public ChannelSession findOrCreateActive(Channel channel, String firstMessageHint) {
        LocalDateTime threshold = LocalDateTime.now().minus(SESSION_TTL);
        List<ChannelSession> active = channelSessionRepository.findActive(channel.getId(), threshold);
        if (!active.isEmpty()) {
            return active.get(0);
        }
        ChannelSession session = ChannelSession.builder()
                .channelId(channel.getId())
                .title(buildTitle(firstMessageHint))
                .lastMessageAt(LocalDateTime.now())
                .build();
        ChannelSession saved = channelSessionRepository.save(session);
        log.info("Created new channel session id={} for channel id={}", saved.getId(), channel.getId());
        return saved;
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
