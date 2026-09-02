package ru.agimate.controlapi.service.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.manage.dto.session.SessionLastMessage;
import ru.agimate.controlapi.controller.manage.dto.session.SessionMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;
import ru.agimate.controlapi.service.webchat.WebchatAttachment;
import ru.agimate.controlapi.service.webchat.WebchatPreviews;
import ru.agimate.controlapi.storage.SignedFileUrlService;
import ru.agimate.controlapi.util.SqlValues;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The session as a resource of {@code /manage}: listing, renaming, closing, history, read pointer —
 * everything that is true of a conversation regardless of what carries it. The transport-specific
 * half (starting a webchat, sending into it, its Centrifugo tokens) stays in
 * {@code WebchatService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ManageSessionService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSessionService agentSessionService;
    private final AgentRunQueryService agentRunQueryService;
    private final WebchatMessageRepository webchatMessageRepository;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final SignedFileUrlService signedFileUrlService;

    /**
     * The user's conversations, freshest first. The three marks a chat list needs — unread, preview,
     * «working now» — are three batch queries for the whole page, never one per row.
     */
    public Page<SessionResponse> list(UUID userId, UUID agentId, UUID channelId, String connectorCode,
                                      int page, int size) {
        Page<AgentSession> sessions = agentSessionService.list(
                userId, agentId, channelId, connectorCode, null, null,
                page, Math.min(size, MAX_PAGE_SIZE));
        return sessions.map(enricher(sessions.getContent()));
    }

    public SessionResponse get(UUID userId, UUID sessionId) {
        AgentSession session = requireOwned(userId, sessionId);
        return enricher(List.of(session)).apply(session);
    }

    @Transactional
    public SessionResponse rename(UUID userId, UUID sessionId, String title) {
        AgentSession session = requireOwned(userId, sessionId);
        AgentSession renamed = agentSessionService.rename(session, title);
        return enricher(List.of(renamed)).apply(renamed);
    }

    /**
     * Closing a conversation also ends it as unread: a closed chat keeps its history but stops
     * asking for attention in the listings.
     */
    @Transactional
    public SessionResponse close(UUID userId, UUID sessionId) {
        requireOwned(userId, sessionId);
        agentSessionService.markReadThroughLatest(sessionId);
        AgentSession closed = agentSessionService.close(sessionId);
        return enricher(List.of(closed)).apply(closed);
    }

    @Transactional
    public void markRead(UUID userId, UUID sessionId, UUID lastReadMessageId) {
        requireOwned(userId, sessionId);
        agentSessionService.markRead(sessionId, lastReadMessageId);
    }

    /**
     * The session's history, newest first (page 0 is the freshest; the frontend reverses it when
     * rendering). Which store answers is decided by the connector — see
     * {@link SessionMessageResponse}.
     */
    public Page<SessionMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size) {
        AgentSession session = requireOwned(userId, sessionId);
        // created_at comes from CURRENT_TIMESTAMP, which is the same for every row a run writes in one
        // transaction — the id (uuidv7) is what keeps a page from repeating or skipping one of them.
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        if (WebchatChannelHandler.CONNECTOR_CODE.equals(session.getConnectorCode())) {
            return webchatMessageRepository.findBySessionId(sessionId, pageRequest)
                    .map(message -> SessionMessageResponse.from(message, attachments(message)));
        }
        return channelSessionMessageRepository.findWithMessageBySessionId(sessionId, pageRequest)
                .map(SessionMessageResponse::from);
    }

    /**
     * The badge, the preview and «working now» read the webchat UI log, so a session of another
     * connector comes back with zeroes there — an external messenger keeps that state itself.
     */
    private Function<AgentSession, SessionResponse> enricher(List<AgentSession> sessions) {
        List<UUID> sessionIds = sessions.stream().map(AgentSession::getId).toList();
        if (sessionIds.isEmpty()) {
            return SessionResponse::from;
        }
        Map<UUID, Long> unread = webchatMessageRepository.countUnreadBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
        Map<UUID, SessionLastMessage> previews = webchatMessageRepository
                .findLastMessagesBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> new SessionLastMessage(
                        WebchatPreviews.shorten((String) row[2]),
                        (String) row[1],
                        Boolean.TRUE.equals(row[3]),
                        SqlValues.localDateTime(row[4]))));
        Set<UUID> live = agentRunQueryService.liveSessionIds(sessionIds);

        return session -> SessionResponse.from(
                session,
                unread.getOrDefault(session.getId(), 0L),
                previews.get(session.getId()),
                live.contains(session.getId()));
    }

    /** Stored parts plus a fresh signed link to the contents (expired links are never stored). */
    private List<WebchatAttachment> attachments(WebchatMessage message) {
        return WebchatAttachment.fromStored(message.getParts(), message.getUserId(), signedFileUrlService::issue);
    }

    private AgentSession requireOwned(UUID userId, UUID sessionId) {
        AgentSession session = agentSessionService.getById(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return session;
    }
}
