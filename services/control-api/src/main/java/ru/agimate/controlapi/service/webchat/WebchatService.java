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
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatFileResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendMessageRequest;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSessionResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.entities.WebchatMessage;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
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
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Оркестрация webchat: одна USER-scope connection на пользователя (материализуется binding'ом при
 * первом чате), per-agent канал с handler'ом {@code webchat}, явные сессии ({@code channel_sessions}).
 * Входящее сообщение уходит штатным триггер-пайплайном: audience таргетирует агента канала (shared
 * connection — без audience был бы fanout на всех привязанных), declared prompt несёт выбранную
 * фронтом сессию.
 *
 * <p>Без класс-левел {@code @Transactional(readOnly = true)}: {@link #send} обязан выполняться вне
 * транзакции (иначе вложенные записи присоединяются к read-only, а DBOS-enqueue попадает в общую
 * транзакцию с историей) — читающие методы размечены точечно.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebchatService {

    private final AgentRepository agentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final ChannelSessionService channelSessionService;
    private final ConnectionBindingService connectionBindingService;
    private final TriggerRouterService triggerRouterService;
    private final WebchatMessagePublisher webchatMessagePublisher;
    private final WebchatMessageRepository webchatMessageRepository;
    private final CentrifugoService centrifugoService;
    private final SignedFileUrlService signedFileUrlService;
    private final FileStorageService fileStorageService;
    private final InboundRateLimiter rateLimiter;

    /** Потолок вложений в одном сообщении — защита промпта/квоты от абьюза. */
    private static final int MAX_PARTS = 5;

    /** Новая сессия чата с агентом; binding и канал материализуются лениво (find-or-create). */
    @Transactional
    public WebchatSessionResponse startSession(UUID userId, UUID agentId) {
        Agent agent = requireOwnedAgent(userId, agentId);
        AgentConnection binding = connectionBindingService.bind(
                userId, agentId, WebchatChannelHandler.CONNECTOR_CODE, null, null);
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

        ChannelSession session = channelSessionService.createNew(channel, null);
        return WebchatSessionResponse.from(session, agentId);
    }

    /** Все webchat-сессии пользователя (опционально одного агента), свежие сверху. */
    @Transactional(readOnly = true)
    public List<WebchatSessionResponse> listSessions(UUID userId, UUID agentId) {
        List<Channel> channels = channelRepository
                .findByUserIdAndConnectorCodeAndDeletedAtIsNull(userId, WebchatChannelHandler.CONNECTOR_CODE)
                .stream()
                .filter(c -> agentId == null || agentId.equals(c.getAgentId()))
                .toList();
        if (channels.isEmpty()) {
            return List.of();
        }
        Map<UUID, Channel> byId = channels.stream()
                .collect(Collectors.toMap(Channel::getId, Function.identity()));
        return channelSessionService.listByChannelIds(List.copyOf(byId.keySet())).stream()
                .map(s -> WebchatSessionResponse.from(s, byId.get(s.getChannelId()).getAgentId()))
                .toList();
    }

    /**
     * Загрузить файл для последующей отправки в сообщении (parts). Файл кладётся в файловый слой на
     * имя пользователя; фронт получает {@code fileId} и передаёт его в {@code parts} при {@link #send}.
     */
    public WebchatFileResponse uploadFile(UUID userId, MultipartFile file) {
        // До обращения к хранилищу: ключ ведра — сам пользователь.
        if (!rateLimiter.tryAcquire(InboundRateLimiter.Scope.FILE_UPLOAD, userId)) {
            throw new TooManyRequestsStatusException("File upload rate limit exceeded");
        }
        String mime = file.getContentType() != null && !file.getContentType().isBlank()
                ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        // Контент пользовательский — в лог только размеры/метаданные.
        log.info("Webchat file upload - user={}, mime={}, {} bytes", userId, mime, file.getSize());
        StoredFile stored;
        try (InputStream content = file.getInputStream()) {
            stored = fileStorageService.store(userId, "webchat", mime, file.getSize(), content, null);
        } catch (IOException e) {
            throw new BadRequestStatusException("Failed to read uploaded file: " + e.getMessage());
        }
        return WebchatFileResponse.from(stored);
    }

    /**
     * Принять сообщение пользователя: строка UI-истории + echo-событие, затем штатный
     * триггер-пайплайн (синхронно — ошибки маршрутизации видны фронту сразу). Не транзакционно:
     * DBOS-enqueue внутри роутера не должен жить в общей транзакции с записью истории.
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

        ChannelSession session = ctx.session();
        Channel channel = ctx.channel();
        String messageId = UUID.randomUUID().toString();

        channelSessionService.setTitleIfEmpty(session, request.text());
        channelSessionService.bumpLastMessageAt(session);
        webchatMessagePublisher.record(userId, channel.getAgentId(), channel.getId(), session.getId(),
                WebchatMessageDirection.USER, null, messageId, request.text(), parts);

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

    /** Валидирует вложения запроса (свой + READY + не протух) и строит {@link Part}-список. */
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
            parts.add(new Part(Part.typeForMime(mime), fileId, mime, file.getSizeBytes(), Map.of()));
        }
        return parts;
    }

    /** Parts → data триггера ({@code type/fileId/mime/size}) — handler смапит их в inbound без БД. */
    private static List<Map<String, Object>> dataParts(List<Part> parts) {
        return parts.stream().map(part -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", part.type());
            m.put("fileId", part.storageRef());
            m.put("mime", part.mime());
            m.put("size", part.size());
            return (Map<String, Object>) m;
        }).toList();
    }

    /** UI-история сессии, новые сначала (page=0 — свежие; фронт разворачивает при отрисовке). */
    @Transactional(readOnly = true)
    public Page<WebchatMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size) {
        requireOwnedWebchatSession(userId, sessionId);
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return webchatMessageRepository.findBySessionId(sessionId, pageRequest)
                .map(message -> WebchatMessageResponse.from(message, attachments(message)));
    }

    /** Хранимые parts + свежая подписанная ссылка на содержимое (протухшие ссылки не хранятся). */
    private List<WebchatAttachment> attachments(WebchatMessage message) {
        return WebchatAttachment.fromStored(message.getParts(), signedFileUrlService::issue);
    }

    @Transactional
    public WebchatSessionResponse closeSession(UUID userId, UUID sessionId) {
        SessionContext ctx = requireOwnedWebchatSession(userId, sessionId);
        ChannelSession closed = channelSessionService.close(sessionId);
        return WebchatSessionResponse.from(closed, ctx.channel().getAgentId());
    }

    /** Centrifugo-токены на канал {@code webchat:{sessionId}} — live-события этой сессии. */
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
        ChannelSession session = channelSessionService.getById(sessionId);
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

    private record SessionContext(ChannelSession session, Channel channel) {}
}
