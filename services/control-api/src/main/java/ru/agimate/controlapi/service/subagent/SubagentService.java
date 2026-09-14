package ru.agimate.controlapi.service.subagent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentRun;
import ru.agimate.controlapi.database.entities.AgentSession;
import ru.agimate.controlapi.database.entities.Channel;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.channel.ChannelService;
import ru.agimate.controlapi.service.channel.handler.SubagentChannelHandler;
import ru.agimate.controlapi.service.runcontext.RunCatalog;
import ru.agimate.controlapi.service.session.AgentSessionService;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.ChannelsCodec;
import ru.agimate.controlapi.service.trigger.RunActivityService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Subagents of a conversation: an agent hands a request to a copy of itself, which works in a session
 * of the {@code subagents} channel and reports back into the conversation (docs/decisions/subagents.md).
 * This class owns what must hold in the database — who may ask, the cap, the child's session; routing
 * the request and delivering the report happen outside its transactions.
 *
 * <p>Refusals are {@link ConnectorException}s: the only caller is the connector's tool, and the message
 * is what the model reads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubagentService {

    public static final String CONNECTOR_CODE = RunCatalog.SUBAGENTS;
    public static final String REQUEST_TRIGGER = "request_received";
    public static final String REPORT_TRIGGER = "report_received";

    /**
     * Subagents one conversation may have working at once. Worker model slots are shared by every
     * user, and the calls of one turn arrive together — a model asking for ten at once would take
     * them all.
     */
    static final int MAX_WORKING = 3;

    /** Children listed in the conversation's prompt block: the working ones fit under the cap, plus a few that reported. */
    static final int LISTED_CHILDREN = MAX_WORKING + 5;

    private final AgentRunRepository agentRunRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final AgentSessionService agentSessionService;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final AgentDeliveryService agentDeliveryService;

    /** Whether the request starts a new subagent or adds to an existing one. */
    public enum Mode { NEW, APPEND }

    /**
     * The child the request goes to.
     *
     * @param conversationId the session the subagent works for — where its report will arrive
     */
    public record Target(UUID channelId, UUID childSessionId, UUID conversationId, Mode mode) {}

    /** One subagent of a conversation, as the conversation's prompt block shows it. */
    public record Child(UUID sessionId, String title, boolean working) {}

    /**
     * Resolve where a request from {@code runId} goes: a new child session, or {@code subagentId} when
     * the request adds to an existing one. The conversation's row is locked for the length of the
     * check, so parallel calls of one turn see each other's children.
     */
    @Transactional
    public Target open(UUID agentId, UUID runId, String connectionId, String title, UUID subagentId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ConnectorException("ask_subagent: the calling run is gone"));
        Agent agent = run.getAgent();
        if (!agent.getId().equals(agentId)) {
            throw new ConnectorException("ask_subagent: the run belongs to another agent");
        }
        if (!agentDeliveryService.supportsPush(agent)) {
            throw new ConnectorException("Subagents are not available to an agent of type " + agent.getType());
        }
        // A conversation to report into: a run of a cron or a webhook has no one to tell the result to.
        if (Channels.sessionIdOf(ChannelsCodec.fromMap(run.getChannels())) == null) {
            throw new ConnectorException("Subagents can only be asked from a conversation; this run has none");
        }
        AgentSession conversation = agentSessionRepository.lockById(run.getSessionId())
                .orElseThrow(() -> new ConnectorException("ask_subagent: the conversation is gone"));
        if (conversation.getParentSessionId() != null) {
            throw new ConnectorException("A subagent cannot ask subagents of its own");
        }

        if (subagentId != null) {
            AgentSession child = agentSessionRepository.findById(subagentId)
                    .filter(s -> conversation.getId().equals(s.getParentSessionId()))
                    .filter(s -> agentId.equals(s.getAgentId()))
                    // Closed means gone: the router would hand the request to the channel's active
                    // session instead — another subagent.
                    .filter(s -> s.getClosedAt() == null)
                    .orElseThrow(() -> new ConnectorException(
                            "Unknown or closed subagent_id for this conversation: " + subagentId));
            agentSessionRepository.touch(child.getId(), LocalDateTime.now());
            return new Target(child.getChannelId(), child.getId(), conversation.getId(), Mode.APPEND);
        }

        long working = countWorking(conversation.getId());
        if (working >= MAX_WORKING) {
            throw new ConnectorException(working + " subagents are still working in this conversation; "
                    + "wait for a report before asking another");
        }
        Channel channel = channelOf(agent, connectionId);
        AgentSession child = agentSessionService.createChild(channel, conversation.getId(), title);
        return new Target(channel.getId(), child.getId(), conversation.getId(), Mode.NEW);
    }

    /** Subagents of the conversation that still owe a report. */
    public long countWorking(UUID conversationId) {
        return agentSessionRepository.countWorkingChildren(conversationId, liveSince());
    }

    /** The conversation's most recent subagents, the working ones marked. */
    public List<Child> children(UUID conversationId) {
        List<AgentSession> sessions = agentSessionRepository.findChildren(
                conversationId, PageRequest.of(0, LISTED_CHILDREN));
        if (sessions.isEmpty()) {
            return List.of();
        }
        Set<UUID> live = Set.copyOf(agentRunRepository.findLiveSessionIds(
                sessions.stream().map(AgentSession::getId).toList(), liveSince()));
        return sessions.stream()
                .map(s -> new Child(s.getId(), s.getTitle(), live.contains(s.getId())))
                .toList();
    }

    /** The session a subagent works for; {@code null} when {@code sessionId} is not a subagent's. */
    public UUID conversationOf(UUID sessionId) {
        return agentSessionRepository.findById(sessionId)
                .map(AgentSession::getParentSessionId)
                .orElse(null);
    }

    /** The agent's subagents channel over its connection, created on the first request — as webchat does. */
    private Channel channelOf(Agent agent, String connectionId) {
        UUID connection = UUID.fromString(connectionId);
        return channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                        agent.getId(), CONNECTOR_CODE, connection)
                .orElseGet(() -> channelService.create(agent.getUserId(), new ChannelService.CreateChannelData(
                        agent.getId(),
                        "Subagents: " + agent.getName(),
                        SubagentChannelHandler.NAME,
                        CONNECTOR_CODE,
                        connectionId,
                        Map.of(),
                        null)));
    }

    private static LocalDateTime liveSince() {
        return LocalDateTime.now().minus(RunActivityService.STALE_AFTER);
    }
}
