package ru.agimate.controlapi.service.acp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.entities.ChannelSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.channel.handler.AcpChannelHandler;
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

/**
 * Оркестрация ACP-диалога (структурный близнец {@code WebchatService}): одна USER-scope
 * connection на пользователя, per-agent канал с handler'ом {@code acp}, явные сессии
 * ({@code channel_sessions}). Входящее сообщение уходит штатным триггер-пайплайном; в отличие
 * от webchat отдельной UI-истории нет — реплей {@code session/load} читает
 * {@code channel_session_messages} (INBOUND пишет воркер через SaveMessage).
 *
 * <p>Без класс-левел {@code @Transactional(readOnly = true)}: {@link #prompt} обязан выполняться
 * вне транзакции (DBOS-enqueue внутри роутера не должен жить в общей транзакции с историей).
 *
 * <p>Исключения — {@code *StatusException}: этот сервис — граница ACP-транспорта, WebSocket-хендлер
 * мапит их в JSON-RPC ошибки так же, как advice мапит в HTTP-статусы.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpService {

    private final AgentRepository agentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final ChannelSessionService channelSessionService;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final ConnectionBindingService connectionBindingService;
    private final TriggerRouterService triggerRouterService;

    /** Новая ACP-сессия; binding и канал материализуются лениво (find-or-create). */
    @Transactional
    public ChannelSession startSession(UUID userId, UUID agentId) {
        Agent agent = requireOwnedAgent(userId, agentId);
        AgentConnection binding = connectionBindingService.bind(
                userId, agentId, AcpChannelHandler.CONNECTOR_CODE, null, null);
        UUID connectionId = binding.getConnectionId();

        Channel channel = channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                        agentId, AcpChannelHandler.CONNECTOR_CODE, connectionId)
                .orElseGet(() -> channelService.create(userId, new ChannelService.CreateChannelData(
                        agentId,
                        "ACP: " + agent.getName(),
                        AcpChannelHandler.NAME,
                        AcpChannelHandler.CONNECTOR_CODE,
                        connectionId.toString(),
                        Map.of(),
                        null)));

        return channelSessionService.createNew(channel, null);
    }

    /** История сессии для реплея {@code session/load}, старые сначала. Проверяет владение. */
    @Transactional(readOnly = true)
    public List<ChannelSessionMessage> loadSession(UUID userId, UUID agentId, UUID sessionId) {
        requireOwnedAcpSession(userId, agentId, sessionId);
        return channelSessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /** Проверка владения без загрузки истории — восстановление привязки после реконнекта моста. */
    @Transactional(readOnly = true)
    public void assertOwned(UUID userId, UUID agentId, UUID sessionId) {
        requireOwnedAcpSession(userId, agentId, sessionId);
    }

    /**
     * Принять сообщение пользователя из IDE: штатный триггер-пайплайн (синхронно — ошибки
     * маршрутизации видны клиенту сразу). Не транзакционно: DBOS-enqueue внутри роутера не
     * должен жить в одной транзакции с записями сессии.
     */
    public String prompt(UUID userId, UUID agentId, UUID sessionId, String text) {
        SessionContext ctx = requireOwnedAcpSession(userId, agentId, sessionId);
        if (ctx.channel().getDeletedAt() != null) {
            throw new BadRequestStatusException("ACP channel is deleted");
        }
        if (ctx.session().getClosedAt() != null) {
            throw new BadRequestStatusException("ACP session is closed");
        }

        ChannelSession session = ctx.session();
        Channel channel = ctx.channel();
        String messageId = UUID.randomUUID().toString();

        channelSessionService.setTitleIfEmpty(session, text);
        channelSessionService.bumpLastMessageAt(session);

        Trigger trigger = Trigger.createDirected(
                AcpChannelHandler.CONNECTOR_CODE,
                channel.getConnectionId().toString(),
                AcpChannelHandler.TRIGGER_MESSAGE_RECEIVED,
                Map.of(
                        "sessionId", session.getId().toString(),
                        "messageId", messageId,
                        "text", text),
                new TriggerContext(
                        new TriggerAudience(null, List.of(channel.getAgentId())),
                        Channels.ofPrompt(new ChannelInfo(channel.getId(), session.getId(), null))));
        triggerRouterService.routeTrigger(userId, trigger);

        return messageId;
    }

    private Agent requireOwnedAgent(UUID userId, UUID agentId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
        return agent;
    }

    /** Сессия должна принадлежать пользователю, ACP-каналу и агенту ключа этого соединения. */
    private SessionContext requireOwnedAcpSession(UUID userId, UUID agentId, UUID sessionId) {
        ChannelSession session = channelSessionService.getById(sessionId);
        Channel channel = channelRepository.findById(session.getChannelId())
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!channel.getUserId().equals(userId) || !channel.getAgentId().equals(agentId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        if (!AcpChannelHandler.CONNECTOR_CODE.equals(channel.getConnectorCode())) {
            throw new BadRequestStatusException("Not an ACP session");
        }
        return new SessionContext(session, channel);
    }

    private record SessionContext(ChannelSession session, Channel channel) {}
}
