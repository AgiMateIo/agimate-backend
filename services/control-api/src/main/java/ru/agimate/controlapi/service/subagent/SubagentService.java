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
import ru.agimate.controlapi.database.repositories.AgentRepository;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Children of a conversation: an agent hands a request to a copy of itself (a subagent,
 * docs/decisions/subagents.md) or to another agent of its team (docs/decisions/agent-to-agent-internal.md).
 * Both work in a session of the callee's channel whose {@code parent_session_id} is the conversation,
 * and report back into it. This class owns what must hold in the database — who may ask, the cap, the
 * child's session; routing the request and delivering the report happen outside its transactions.
 *
 * <p>Refusals are {@link ConnectorException}s: the only callers are the connectors' tools, and the message
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
     * Children one conversation may have working at once, subagents and agents together. Worker
     * model slots are shared by every user, and the calls of one turn arrive together — a model
     * asking for ten at once would take them all.
     */
    static final int MAX_WORKING = 3;

    /** Children listed in the conversation's prompt block: the working ones fit under the cap, plus a few that reported. */
    static final int LISTED_CHILDREN = MAX_WORKING + 5;

    private final AgentRunRepository agentRunRepository;
    private final AgentSessionRepository agentSessionRepository;
    private final AgentSessionService agentSessionService;
    private final AgentRepository agentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final AgentDeliveryService agentDeliveryService;

    /** Whether the request starts a new child or adds to an existing one. */
    public enum Mode { NEW, APPEND }

    /**
     * The side that does the work: whose session the request opens and in which channel. The
     * channel is per callee agent and connector, created on the first request — as webchat does.
     *
     * @param channelName the name the channel is created with
     */
    public record Callee(Agent agent, String connectorCode, String channelHandler, String channelName) {

        /** The asking agent itself — a subagent. */
        static Callee self(Agent agent) {
            return new Callee(agent, CONNECTOR_CODE, SubagentChannelHandler.NAME, "Subagents: " + agent.getName());
        }
    }

    /**
     * The child the request goes to.
     *
     * @param conversationId the session the child works for — where its report will arrive
     */
    public record Target(UUID channelId, UUID childSessionId, UUID conversationId, Mode mode) {}

    /**
     * One child of a conversation, as the conversation's prompt block shows it.
     *
     * @param agentName the callee's name when it is another agent; {@code null} for a subagent
     */
    public record Child(UUID sessionId, String title, boolean working, String agentName) {}

    /**
     * Resolve where a request from {@code runId} to a copy of the agent goes: a new child session,
     * or {@code childId} when the request adds to an existing one.
     */
    @Transactional
    public Target open(UUID agentId, UUID runId, String connectionId, String title, UUID childId) {
        return open(Callee::self, agentId, runId, connectionId, title, childId);
    }

    /**
     * The same for a request to {@code callee}, another agent. Whether the asker may address it is
     * the caller's business; here the callee only has to own the child session on an append.
     */
    @Transactional
    public Target openFor(Callee callee, UUID agentId, UUID runId, String connectionId, String title, UUID childId) {
        return open(asker -> callee, agentId, runId, connectionId, title, childId);
    }

    /**
     * The conversation's row is locked for the length of the check, so parallel calls of one turn see
     * each other's children.
     */
    private Target open(Function<Agent, Callee> calleeOf, UUID agentId, UUID runId, String connectionId,
                        String title, UUID childId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new ConnectorException("The calling run is gone"));
        Agent asker = run.getAgent();
        if (!asker.getId().equals(agentId)) {
            throw new ConnectorException("The run belongs to another agent");
        }
        // The report comes back as a run of the asker: it needs a transport to be pushed to.
        if (!agentDeliveryService.supportsPush(asker)) {
            throw new ConnectorException("Requests are not available to an agent of type " + asker.getType());
        }
        // A conversation to report into: a run of a cron or a webhook has no one to tell the result to.
        if (Channels.sessionIdOf(ChannelsCodec.fromMap(run.getChannels())) == null) {
            throw new ConnectorException("Requests can only be made from a conversation; this run has none");
        }
        AgentSession conversation = agentSessionRepository.lockById(run.getSessionId())
                .orElseThrow(() -> new ConnectorException("The conversation is gone"));
        if (conversation.getParentSessionId() != null) {
            throw new ConnectorException("A run working on a request cannot ask subagents or agents of its own");
        }
        Callee callee = calleeOf.apply(asker);

        if (childId != null) {
            AgentSession child = agentSessionRepository.findById(childId)
                    .filter(s -> conversation.getId().equals(s.getParentSessionId()))
                    .filter(s -> callee.agent().getId().equals(s.getAgentId()))
                    // Closed means gone: the router would hand the request to the channel's active
                    // session instead — another child.
                    .filter(s -> s.getClosedAt() == null)
                    .orElseThrow(() -> new ConnectorException(
                            "Unknown or closed child id for this conversation: " + childId));
            agentSessionRepository.touch(child.getId(), LocalDateTime.now());
            return new Target(child.getChannelId(), child.getId(), conversation.getId(), Mode.APPEND);
        }

        long working = countWorking(conversation.getId());
        if (working >= MAX_WORKING) {
            throw new ConnectorException(working + " requests (subagents and agents together) are still "
                    + "working in this conversation; wait for a report before asking another");
        }
        Channel channel = channelOf(callee, connectionId);
        AgentSession child = agentSessionService.createChild(channel, conversation.getId(), title);
        return new Target(channel.getId(), child.getId(), conversation.getId(), Mode.NEW);
    }

    /** Children of the conversation that still owe a report. */
    public long countWorking(UUID conversationId) {
        return agentSessionRepository.countWorkingChildren(conversationId, liveSince());
    }

    /** The conversation's most recent children, the working ones marked. */
    public List<Child> children(UUID conversationId) {
        List<AgentSession> sessions = agentSessionRepository.findChildren(
                conversationId, PageRequest.of(0, LISTED_CHILDREN));
        if (sessions.isEmpty()) {
            return List.of();
        }
        Set<UUID> live = Set.copyOf(agentRunRepository.findLiveSessionIds(
                sessions.stream().map(AgentSession::getId).toList(), liveSince()));
        Map<UUID, String> calleeNames = calleeNames(sessions);
        return sessions.stream()
                .map(s -> new Child(s.getId(), s.getTitle(), live.contains(s.getId()), calleeNames.get(s.getAgentId())))
                .toList();
    }

    /** Names of the other agents among the children; a subagent's session is the asker's own. */
    private Map<UUID, String> calleeNames(List<AgentSession> sessions) {
        Set<UUID> others = sessions.stream()
                .filter(s -> !CONNECTOR_CODE.equals(s.getConnectorCode()))
                .map(AgentSession::getAgentId)
                .collect(Collectors.toSet());
        if (others.isEmpty()) {
            return Map.of();
        }
        return agentRepository.findAllById(others).stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName));
    }

    /**
     * Whether {@code sessionId} is a child session of the {@code connectorCode} channel — the role of
     * the runs in it. The parent alone does not tell: a subagent's session and another agent's thread
     * both have one.
     */
    public boolean isChildOf(UUID sessionId, String connectorCode) {
        return agentSessionRepository.findById(sessionId)
                .filter(s -> s.getParentSessionId() != null)
                .map(s -> connectorCode.equals(s.getConnectorCode()))
                .orElse(false);
    }

    /** The callee's channel of the connector over its connection, created on the first request. */
    private Channel channelOf(Callee callee, String connectionId) {
        UUID connection = UUID.fromString(connectionId);
        Agent agent = callee.agent();
        return channelRepository.findByAgentIdAndConnectorCodeAndConnectionIdAndDeletedAtIsNull(
                        agent.getId(), callee.connectorCode(), connection)
                .orElseGet(() -> channelService.create(agent.getUserId(), new ChannelService.CreateChannelData(
                        agent.getId(),
                        callee.channelName(),
                        callee.channelHandler(),
                        callee.connectorCode(),
                        connectionId,
                        Map.of(),
                        null)));
    }

    private static LocalDateTime liveSince() {
        return LocalDateTime.now().minus(RunActivityService.STALE_AFTER);
    }
}
