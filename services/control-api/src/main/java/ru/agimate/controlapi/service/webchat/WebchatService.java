package ru.agimate.controlapi.service.webchat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.app.dto.CentrifugoTokenResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatMessageResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendMessageRequest;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSendResponse;
import ru.agimate.controlapi.controller.manage.dto.webchat.WebchatSessionResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.channel.handler.WebchatChannelHandler;
import ru.agimate.controlapi.service.connection.ConnectionBindingService;
import ru.agimate.controlapi.service.trigger.ChannelInfo;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerAudience;
import ru.agimate.controlapi.service.trigger.TriggerContext;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;

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
        if (request.parts() != null && !request.parts().isEmpty()) {
            throw new BadRequestStatusException("Attachments are not supported yet");
        }

        ChannelSession session = ctx.session();
        Channel channel = ctx.channel();
        String messageId = UUID.randomUUID().toString();

        channelSessionService.setTitleIfEmpty(session, request.text());
        channelSessionService.bumpLastMessageAt(session);
        webchatMessagePublisher.record(userId, channel.getAgentId(), channel.getId(), session.getId(),
                WebchatMessageDirection.USER, null, messageId, request.text());

        Trigger trigger = Trigger.createDirected(
                WebchatChannelHandler.CONNECTOR_CODE,
                channel.getConnectionId().toString(),
                WebchatChannelHandler.TRIGGER_MESSAGE_RECEIVED,
                Map.of(
                        "sessionId", session.getId().toString(),
                        "messageId", messageId,
                        "text", request.text()),
                new TriggerContext(
                        new TriggerAudience(null, List.of(channel.getAgentId())),
                        Channels.ofPrompt(new ChannelInfo(channel.getId(), session.getId(), null))));
        triggerRouterService.routeTrigger(userId, trigger);

        return new WebchatSendResponse(session.getId(), messageId);
    }

    /** UI-история сессии, новые сначала (page=0 — свежие; фронт разворачивает при отрисовке). */
    @Transactional(readOnly = true)
    public Page<WebchatMessageResponse> listMessages(UUID userId, UUID sessionId, int page, int size) {
        requireOwnedWebchatSession(userId, sessionId);
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return webchatMessageRepository.findBySessionId(sessionId, pageRequest)
                .map(WebchatMessageResponse::from);
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
