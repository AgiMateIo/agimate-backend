package ru.agimate.controlapi.service.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.PageRequests;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.controlapi.controller.manage.dto.session.SessionMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.session.SessionResponse;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.service.channel.handler.ChannelMessageLog;
import ru.agimate.controlapi.service.channel.handler.ChannelMessageLogResolver;
import ru.agimate.controlapi.service.view.SessionToolCallService;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

    private final AgentSessionService agentSessionService;
    private final SessionRows sessionRows;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final ChannelMessageLogResolver channelMessageLogResolver;
    private final SessionToolCallService sessionToolCallService;

    /** The user's conversations, freshest first; each row is built by {@link SessionRows}. */
    public Page<SessionResponse> list(UUID userId, UUID agentId, UUID channelId, String connectorCode,
                                      UUID parentSessionId, int page, int size) {
        Page<AgentSession> sessions = agentSessionService.list(
                userId, agentId, channelId, connectorCode, parentSessionId, null, null,
                page, size);
        return sessions.map(sessionRows.mapper(sessions.getContent()));
    }

    public SessionResponse get(UUID userId, UUID sessionId) {
        return sessionRows.of(requireOwned(userId, sessionId));
    }

    @Transactional
    public SessionResponse rename(UUID userId, UUID sessionId, String title) {
        AgentSession session = requireOwned(userId, sessionId);
        AgentSession renamed = agentSessionService.rename(session, title);
        return sessionRows.of(renamed);
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
        return sessionRows.of(closed);
    }

    @Transactional
    public void markRead(UUID userId, UUID sessionId, UUID lastReadMessageId) {
        requireOwned(userId, sessionId);
        agentSessionService.markRead(sessionId, lastReadMessageId);
    }

    /**
     * The session's history, newest first (page 0 is the freshest; the frontend reverses it when
     * rendering). Which store answers is decided by the channel plugin: a handler with its own
     * {@link ChannelMessageLog} reads it, everyone else is served by the platform's generic
     * projection — see {@link SessionMessageResponse}.
     */
    public Page<SessionMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size) {
        AgentSession session = requireOwned(userId, sessionId);
        return withToolCalls(session, readMessages(session, userId, page, size));
    }

    private Page<SessionMessageResponse> readMessages(AgentSession session, UUID userId, int page, int size) {
        Optional<ChannelMessageLog> ownLog = channelMessageLogResolver.find(session);
        if (ownLog.isPresent()) {
            return ownLog.get().listMessages(userId, session.getId(), page, size);
        }
        // created_at comes from CURRENT_TIMESTAMP, which is the same for every row a run writes in one
        // transaction — the id (uuidv7) is what keeps a page from repeating or skipping one of them.
        PageRequest pageRequest = PageRequests.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return channelSessionMessageRepository.findWithMessageBySessionId(session.getId(), pageRequest)
                .map(SessionMessageResponse::from);
    }

    /**
     * Attaches the calls' descriptors to the page in one resolution: the agent's catalog and the
     * journal are read once for the whole page. A row whose resolution did not happen keeps
     * {@code toolCalls = null} — the frontend then uses the old per-call endpoint.
     */
    private Page<SessionMessageResponse> withToolCalls(AgentSession session, Page<SessionMessageResponse> page) {
        List<String> ids = page.getContent().stream()
                .map(SessionMessageResponse::toolCallIds)
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return page;
        }
        return sessionToolCallService
                .describe(session.getUserId(), session.getAgentId(), session.getId(), ids)
                .map(byId -> page.map(row -> {
                    List<String> rowIds = row.toolCallIds();
                    return rowIds == null ? row : row.withToolCalls(
                            rowIds.stream().map(byId::get).filter(Objects::nonNull).toList());
                }))
                .orElse(page);
    }

    private AgentSession requireOwned(UUID userId, UUID sessionId) {
        AgentSession session = agentSessionService.getById(sessionId);
        if (!session.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return session;
    }
}
