package ru.agimate.controlapi.service.session;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.manage.dto.session.SessionLastMessage;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.webchat.WebchatPreviews;
import ru.agimate.controlapi.util.SqlValues;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The session as a listing row, for {@code /manage} and for its live event alike. Reads only — it
 * depends on repositories, never on a service that writes sessions, so the event side can use it
 * without a cycle.
 *
 * <p>The badge, the preview and «working now» read the webchat UI log, so a session of another
 * connector comes back with zeroes there — an external messenger keeps that state itself.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SessionRows {

    private final AgentSessionRepository agentSessionRepository;
    private final WebchatMessageRepository webchatMessageRepository;
    private final AgentRunQueryService agentRunQueryService;

    public Optional<SessionResponse> find(UUID sessionId) {
        return agentSessionRepository.findById(sessionId).map(this::of);
    }

    public SessionResponse of(AgentSession session) {
        return mapper(List.of(session)).apply(session);
    }

    /** Three batch queries for the whole page, never one per row. */
    public Function<AgentSession, SessionResponse> mapper(List<AgentSession> sessions) {
        List<UUID> sessionIds = sessions.stream().map(AgentSession::getId).toList();
        if (sessionIds.isEmpty()) {
            return SessionResponse::from;
        }
        Map<UUID, Long> unread = webchatMessageRepository.countUnreadBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
        Map<UUID, SessionLastMessage> previews = webchatMessageRepository
                .findLastMessagesBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0],
                        row -> lastMessage(row[1], row[2], row[3], row[4])));
        Set<UUID> live = agentRunQueryService.liveSessionIds(sessionIds);

        return session -> SessionResponse.from(
                session,
                unread.getOrDefault(session.getId(), 0L),
                previews.get(session.getId()),
                live.contains(session.getId()));
    }

    /** The preview of a last message from a raw query row — shared with the contact row. */
    public static SessionLastMessage lastMessage(Object direction, Object text, Object hasAttachments, Object createdAt) {
        return new SessionLastMessage(
                WebchatPreviews.shorten((String) text),
                (String) direction,
                Boolean.TRUE.equals(hasAttachments),
                SqlValues.localDateTime(createdAt));
    }
}
