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
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.session.AgentSessionService;
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
 * Orchestration of an ACP dialogue (the structural twin of {@code WebchatService}): one USER-scope
 * connection per user, a per-agent channel with the {@code acp} handler, explicit sessions
 * ({@code channel_sessions}). An incoming message goes out through the regular trigger pipeline;
 * unlike webchat there is no separate UI history — a {@code session/load} replay reads
 * {@code channel_session_messages} (INBOUND is written by the worker through SaveMessage).
 *
 * <p>No class-level {@code @Transactional(readOnly = true)}: {@link #prompt} must run outside a
 * transaction (the DBOS enqueue inside the router must not share a transaction with the history).
 *
 * <p>The exceptions are {@code *StatusException}: this service is the ACP transport's boundary, and
 * the WebSocket handler maps them into JSON-RPC errors just as the advice maps them into HTTP
 * statuses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpService {

    private final AgentRepository agentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final AgentSessionService agentSessionService;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final ConnectionBindingService connectionBindingService;
    private final TriggerRouterService triggerRouterService;

    /** A new ACP session; the binding and the channel are materialised lazily (find-or-create). */
    @Transactional
    public AgentSession startSession(UUID userId, UUID agentId) {
        Agent agent = requireOwnedAgent(userId, agentId);
        AgentConnection binding = connectionBindingService.bindInternal(
                userId, agentId, AcpChannelHandler.CONNECTOR_CODE);
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

        return agentSessionService.createNew(channel, null);
    }

    /** The session's history for a {@code session/load} replay, oldest first. Checks ownership. */
    @Transactional(readOnly = true)
    public List<ChannelSessionMessage> loadSession(UUID userId, UUID agentId, UUID sessionId) {
        requireOwnedAcpSession(userId, agentId, sessionId);
        return channelSessionMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    /** An ownership check without loading the history — restoring the binding after the bridge reconnects. */
    @Transactional(readOnly = true)
    public void assertOwned(UUID userId, UUID agentId, UUID sessionId) {
        requireOwnedAcpSession(userId, agentId, sessionId);
    }

    /**
     * Accept a user message from the IDE: the regular trigger pipeline (synchronously — routing errors
     * are visible to the client immediately). Not transactional: the DBOS enqueue inside the router
     * must not share a transaction with the session's records.
     */
    public String prompt(UUID userId, UUID agentId, UUID sessionId, String text) {
        SessionContext ctx = requireOwnedAcpSession(userId, agentId, sessionId);
        if (ctx.channel().getDeletedAt() != null) {
            throw new BadRequestStatusException("ACP channel is deleted");
        }
        if (ctx.session().getClosedAt() != null) {
            throw new BadRequestStatusException("ACP session is closed");
        }

        AgentSession session = ctx.session();
        Channel channel = ctx.channel();
        String messageId = UUID.randomUUID().toString();

        agentSessionService.setTitleIfEmpty(session, text);
        agentSessionService.bumpLastActivityAt(session);

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

    /** The session must belong to the user, to the ACP channel and to the agent of this connection's key. */
    private SessionContext requireOwnedAcpSession(UUID userId, UUID agentId, UUID sessionId) {
        AgentSession session = agentSessionService.getById(sessionId);
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

    private record SessionContext(AgentSession session, Channel channel) {}
}
