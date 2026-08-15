package ru.agimate.controlapi.service.webchat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.TooManyRequestsStatusException;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatContactResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatFileResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatLastMessage;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendMessageRequest;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSessionResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.AgentRunQueryService;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.ratelimit.InboundRateLimiter;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.NewFile;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestration of webchat: one USER-scope connection per user (materialised by the binding on the
 * first chat), a per-agent channel with the {@code webchat} handler, explicit sessions
 * ({@code agent_sessions}). An incoming message goes out through the regular trigger pipeline: the
 * audience targets the channel's agent (the connection is shared — without an audience this would fan
 * out to every bound agent), and the declared prompt carries the session the frontend chose.
 *
 * <p>No class-level {@code @Transactional(readOnly = true)}: {@link #send} must run outside a
 * transaction (otherwise the nested writes join a read-only one, and the DBOS enqueue ends up sharing a
 * transaction with the history) — the reading methods are annotated individually.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebchatService {

    private final AgentRepository agentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final AgentSessionService agentSessionService;
    private final AgentRunQueryService agentRunQueryService;
    private final ConnectionBindingService connectionBindingService;
    private final TriggerRouterService triggerRouterService;
    private final WebchatMessagePublisher webchatMessagePublisher;
    private final WebchatMessageRepository webchatMessageRepository;
    private final CentrifugoService centrifugoService;
    private final SignedFileUrlService signedFileUrlService;
    private final FileStorageService fileStorageService;
    private final InboundRateLimiter rateLimiter;

    /** Ceiling on attachments in one message — protection of the prompt and the quota from abuse. */
    private static final int MAX_PARTS = 5;

    /** A new chat session with an agent; the binding and the channel are materialised lazily (find-or-create). */
    @Transactional
    public WebchatSessionResponse startSession(UUID userId, UUID agentId) {
        Agent agent = requireOwnedAgent(userId, agentId);
        AgentConnection binding = connectionBindingService.bindInternal(
                userId, agentId, WebchatChannelHandler.CONNECTOR_CODE);
        UUID connectionId = binding.getConnectionId();

        Channel channel = channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                        agentId, WebchatChannelHandler.CONNECTOR_CODE, connectionId)
                .orElseGet(() -> channelService.create(userId, new ChannelService.CreateChannelData(
                        agentId,
                        "Webchat: " + agent.getName(),
                        WebchatChannelHandler.NAME,
                        WebchatChannelHandler.CONNECTOR_CODE,
                        connectionId.toString(),
                        Map.of(),
                        null)));

        AgentSession session = agentSessionService.createNew(channel, null);
        return WebchatSessionResponse.from(session, agentId);
    }

    /**
     * A user's webchat sessions (optionally for one agent), freshest first. The channels are read
     * whole — there is one per agent, so that set is bounded by how many agents the user has; the
     * sessions behind them are not, and they are the paged half.
     *
     * <p>The three marks a chat list needs — unread, preview, «working now» — are three batch
     * queries for the whole page, never one per row.
     */
    @Transactional(readOnly = true)
    public Page<WebchatSessionResponse> listSessions(UUID userId, UUID agentId, int page, int size) {
        List<Channel> channels = channelRepository
                .findByUserIdAndConnectorCodeAndDeletedAtIsNull(userId, WebchatChannelHandler.CONNECTOR_CODE)
                .stream()
                .filter(c -> agentId == null || agentId.equals(c.getAgentId()))
                .toList();
        if (channels.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }
        Map<UUID, Channel> byId = channels.stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity()));
        Page<AgentSession> sessions = agentSessionService.listByChannelIds(byId.keySet(), page, size);

        List<UUID> sessionIds = sessions.getContent().stream().map(AgentSession::getId).toList();
        Map<UUID, Long> unread = unreadBySession(sessionIds);
        Map<UUID, WebchatLastMessage> previews = lastMessagesBySession(sessionIds);
        Set<UUID> live = agentRunQueryService.liveSessionIds(sessionIds);

        return sessions.map(s -> WebchatSessionResponse.from(
                s,
                byId.get(s.getChannelId()).getAgentId(),
                unread.getOrDefault(s.getId(), 0L),
                previews.get(s.getId()),
                live.contains(s.getId())));
    }

    /**
     * The contact list: the user's agents ordered by the freshness of their chat. The ordering is
     * the reason this is one endpoint and not two — see {@code AgentRepository.findChatContacts}.
     */
    @Transactional(readOnly = true)
    public Page<WebchatContactResponse> listContacts(UUID userId, int page, int size) {
        Page<Object[]> rows = agentRepository.findChatContacts(
                userId, WebchatChannelHandler.CONNECTOR_CODE, PageRequest.of(page, size));

        List<UUID> agentIds = rows.getContent().stream().map(row -> (UUID) row[0]).toList();
        Map<UUID, Long> unread = unreadByAgent(agentIds);
        Map<UUID, ContactPreview> previews = lastMessagesByAgent(agentIds);
        Set<UUID> live = agentRunQueryService.liveAgentIds(agentIds, WebchatChannelHandler.CONNECTOR_CODE);

        return rows.map(row -> {
            UUID id = (UUID) row[0];
            ContactPreview preview = previews.get(id);
            return new WebchatContactResponse(
                    id,
                    (String) row[1],
                    (String) row[2],
                    Boolean.TRUE.equals(row[3]),
                    unread.getOrDefault(id, 0L),
                    preview != null ? preview.message() : null,
                    preview != null ? preview.sessionId() : null,
                    toLocalDateTime(row[4]),
                    live.contains(id));
        });
    }

    /**
     * Read up to {@code lastReadMessageId}, or up to the end of the conversation when the request
     * names no message. The pointer only ever moves forward.
     */
    public void markRead(UUID userId, UUID sessionId, UUID lastReadMessageId) {
        requireOwnedWebchatSession(userId, sessionId);
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
            agentSessionService.advanceReadPointer(sessionId, pointer);
        }
    }

    /** Whatever stood in the conversation up to now has been read. */
    private void markReadThroughLatest(UUID sessionId) {
        webchatMessageRepository.findLastMessageId(sessionId)
                .ifPresent(id -> agentSessionService.advanceReadPointer(sessionId, id));
    }

    private Map<UUID, Long> unreadBySession(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return webchatMessageRepository.countUnreadBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
    }

    private Map<UUID, Long> unreadByAgent(List<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        return webchatMessageRepository.countUnreadByAgentIds(agentIds).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> ((Number) row[1]).longValue()));
    }

    private Map<UUID, WebchatLastMessage> lastMessagesBySession(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return webchatMessageRepository.findLastMessagesBySessionIds(sessionIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> lastMessage(row[1], row[2], row[3], row[4])));
    }

    private Map<UUID, ContactPreview> lastMessagesByAgent(List<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        return webchatMessageRepository.findLastMessagesByAgentIds(agentIds).stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> new ContactPreview((UUID) row[1],
                                lastMessage(row[2], row[3], row[4], row[5]))));
    }

    private static WebchatLastMessage lastMessage(Object direction, Object text,
                                                  Object hasAttachments, Object createdAt) {
        return new WebchatLastMessage(
                WebchatPreviews.shorten((String) text),
                (String) direction,
                Boolean.TRUE.equals(hasAttachments),
                toLocalDateTime(createdAt));
    }

    /** Native queries hand a {@code TIMESTAMP} back as {@link Timestamp}; nothing else lands here. */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    }

    /** The preview of a contact row together with the conversation it came from. */
    private record ContactPreview(UUID sessionId, WebchatLastMessage message) {}

    /**
     * Upload a file to be sent later in a message (parts). The file is placed into the file layer under
     * the user's name; the frontend receives a {@code fileId} and passes it in {@code parts} at
     * {@link #send}.
     */
    public WebchatFileResponse uploadFile(UUID userId, MultipartFile file) {
        // Before touching the storage: the bucket's key is the user themselves.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, userId)) {
            throw new TooManyRequestsStatusException("File upload rate limit exceeded");
        }
        String mime = file.getContentType() != null && !file.getContentType().isBlank()
                ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        // The content belongs to the user — only sizes and metadata go into the log.
        log.info("Webchat file upload - user={}, mime={}, {} bytes", userId, mime, file.getSize());
        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            // No agentId: the upload precedes the send, so the session — and with it the agent — is
            // not yet chosen.
            stored = fileStorageService.store(NewFile.builder()
                    .userId(userId)
                    .origin("webchat")
                    .name(file.getOriginalFilename())
                    .mime(mime)
                    .sizeBytes(file.getSize())
                    .build(), content);
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file: " + e.getMessage());
        }
        return WebchatFileResponse.from(stored);
    }

    /**
     * Accept a user's message: a UI history row plus an echo event, then the regular trigger pipeline
     * (synchronously — routing errors are visible to the frontend immediately). Not transactional: the
     * DBOS enqueue inside the router must not share a transaction with writing the history.
     */
    public WebchatSendResponse send(UUID userId, UUID sessionId, WebchatSendMessageRequest request) {
        SessionContext ctx = requireOwnedWebchatSession(userId, sessionId);
        if (ctx.channel().getDeletedAt() != null) {
            throw new BadRequestStatusException("Webchat channel is deleted");
        }
        if (ctx.session().getClosedAt() != null) {
            throw new BadRequestStatusException("Webchat session is closed");
        }
        List<Part> parts = resolveParts(userId, request.parts());
        boolean textBlank = request.text() == null || request.text().isBlank();
        if (textBlank && parts.isEmpty()) {
            throw new BadRequestStatusException("Message text or attachments required");
        }

        AgentSession session = ctx.session();
        Channel channel = ctx.channel();
        String messageId = UUID.randomUUID().toString();

        agentSessionService.setTitleIfEmpty(session, request.text());
        agentSessionService.bumpLastActivityAt(session);
        webchatMessagePublisher.record(userId, channel.getAgentId(), channel.getId(), session.getId(),
                WebchatMessageDirection.USER, null, messageId, request.text(), parts);
        // Writing into a conversation is reading it: the answer being replied to is not unread.
        markReadThroughLatest(session.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.getId().toString());
        data.put("messageId", messageId);
        if (!textBlank) {
            data.put("text", request.text());
        }
        if (!parts.isEmpty()) {
            data.put("parts", dataParts(parts));
        }
        Trigger trigger = Trigger.createDirected(
                WebchatChannelHandler.CONNECTOR_CODE,
                channel.getConnectionId().toString(),
                WebchatChannelHandler.TRIGGER_MESSAGE_RECEIVED,
                data,
                new TriggerContext(
                        new TriggerAudience(null, List.of(channel.getAgentId())),
                        Channels.ofPrompt(new ChannelInfo(channel.getId(), session.getId(), null))));
        triggerRouterService.routeTrigger(userId, trigger);

        return new WebchatSendResponse(session.getId(), messageId);
    }

    /** Validates the request's attachments (own + READY + not expired) and builds the {@link Part} list. */
    private List<Part> resolveParts(UUID userId, List<Map<String, Object>> requestParts) {
        if (requestParts == null || requestParts.isEmpty()) {
            return List.of();
        }
        if (requestParts.size() > MAX_PARTS) {
            throw new BadRequestStatusException("Too many attachments: max " + MAX_PARTS);
        }
        List<Part> parts = new ArrayList<>(requestParts.size());
        for (Map<String, Object> p : requestParts) {
            Object fileIdRaw = p != null ? p.get("fileId") : null;
            if (fileIdRaw == null || fileIdRaw.toString().isBlank()) {
                throw new BadRequestStatusException("Attachment is missing fileId");
            }
            String fileId = fileIdRaw.toString();
            StoredFile file = fileStorageService.findReadable(userId, fileId)
                    .orElseThrow(() -> new BadRequestStatusException("Attachment not found: " + fileId));
            String mime = file.getMime();
            Map<String, Object> meta = file.getName() != null ? Map.of("name", file.getName()) : Map.of();
            parts.add(new Part(Part.typeForMime(mime), fileId, mime, file.getSizeBytes(), meta));
        }
        return parts;
    }

    /** Parts → the trigger's data ({@code type/fileId/mime/size/name}) — the handler maps them into the inbound message with no database access. */
    private static List<Map<String, Object>> dataParts(List<Part> parts) {
        return parts.stream().map(part -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", part.type());
            m.put("fileId", part.storageRef());
            m.put("mime", part.mime());
            m.put("size", part.size());
            Object name = part.meta().get("name");
            if (name != null) {
                m.put("name", name);
            }
            return (Map<String, Object>) m;
        }).toList();
    }

    /** The session's UI history, newest first (page=0 — the freshest; the frontend reverses it when rendering). */
    @Transactional(readOnly = true)
    public Page<WebchatMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size) {
        requireOwnedWebchatSession(userId, sessionId);
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return webchatMessageRepository.findBySessionId(sessionId, pageRequest)
                .map(message -> WebchatMessageResponse.from(message, attachments(message)));
    }

    /** Stored parts plus a fresh signed link to the contents (expired links are never stored). */
    private List<WebchatAttachment> attachments(WebchatMessage message) {
        return WebchatAttachment.fromStored(message.getParts(), signedFileUrlService::issue);
    }

    /**
     * Closing a conversation also ends it as unread: a closed chat keeps its history but stops
     * asking for attention in the listings.
     */
    @Transactional
    public WebchatSessionResponse closeSession(UUID userId, UUID sessionId) {
        SessionContext ctx = requireOwnedWebchatSession(userId, sessionId);
        markReadThroughLatest(sessionId);
        AgentSession closed = agentSessionService.close(sessionId);
        return WebchatSessionResponse.from(closed, ctx.channel().getAgentId());
    }

    /** Centrifugo tokens for the channel {@code webchat:{sessionId}} — this session's live events. */
    @Transactional(readOnly = true)
    public CentrifugoTokenResponse token(UUID userId, UUID sessionId) {
        requireOwnedWebchatSession(userId, sessionId);
        String channel = WebchatMessagePublisher.CENTRIFUGO_CHANNEL_PREFIX + sessionId;
        return centrifugoService.issueTokens(userId.toString(), channel);
    }

    private Agent requireOwnedAgent(UUID userId, UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
        return agent;
    }

    private SessionContext requireOwnedWebchatSession(UUID userId, UUID sessionId) {
        AgentSession session = agentSessionService.getById(sessionId);
        Channel channel = channelRepository.findById(session.getChannelId())
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!channel.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        if (!WebchatChannelHandler.CONNECTOR_CODE.equals(channel.getConnectorCode())) {
            throw new BadRequestStatusException("Not a webchat session");
        }
        return new SessionContext(session, channel);
    }

    private record SessionContext(AgentSession session, Channel channel) {}
}
