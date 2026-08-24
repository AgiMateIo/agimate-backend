package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AccessDecision;
import ru.agimate.controlapi.abac.ConnectionAccessEvaluator;
import ru.agimate.controlapi.controller.app.dto.TriggerRequest;
import ru.agimate.controlapi.database.entities.*;
import ru.agimate.controlapi.database.enums.PolicyKind;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.enums.FileReferenceKind;
import ru.agimate.controlapi.database.repositories.AgentRunRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.channel.ChannelMessageOutboundService;
import ru.agimate.controlapi.service.channel.InputFilterEvaluator;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.file.FileReferenceService;
import ru.agimate.controlapi.service.channel.handler.dto.OutboundMessage;
import ru.agimate.controlapi.service.seed.ChannelTexts;
import ru.agimate.controlapi.service.session.AgentSessionResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    /** Runs waiting in one session before the backlog is worth a line in the log. */
    private static final int QUEUE_DEPTH_WARN = 20;

    private final TriggerLogService triggerLogService;
    private final TriggerLogProbeService triggerLogProbeService;
    private final ConnectionAccessEvaluator accessEvaluator;
    private final AgentRepository agentRepository;
    private final AgentDeliveryService agentDeliveryService;
    private final ChannelRouteResolver channelRouteResolver;
    private final RunCancellationService runCancellationService;
    private final ChannelMessageOutboundService outboundService;
    private final ChannelTexts channelTexts;
    private final AgentSessionResolver sessionResolver;

    private final AgentRunRepository agentRunRepository;
    private final FileReferenceService fileReferenceService;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        Trigger trigger = Trigger.fromSource(
                app.getConnectorCode(),
                app.getId().toString(),
                triggerRequest.name(),
                triggerRequest.id(),
                JsonUtils.fromJsonToMap(triggerRequest.data().toString()),
                triggerRequest.occurredAt()
        );
        routeTrigger(app.getUserId(), trigger);
    }

    @Async
    public void routeWhTrigger(UUID userId, Trigger trigger) {
        routeTrigger(userId, trigger);
    }


    public void routeTrigger(UUID userId, Trigger trigger) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userId, trigger);

        if (triggerLogProbeService.isBlockProbe(trigger.data())) {
            log.info("Trigger contains discovery probe (block mode) - skipping agent routing for user={}", userId);
            return;
        }

        List<Agent> recipients = findRecipients(userId, trigger);

        List<TriggerRoute> routes = planRoutes(recipients, trigger);
        dispatch(triggerLog, trigger, routes);
    }

    /**
     * Recipients of a trigger (the «who»): candidates with an active binding to the connection (= the
     * trigger's connectionId), narrowed by {@link TriggerAudience}, then per-agent ABAC through
     * {@link ConnectionAccessEvaluator} (default-allow + DENY exceptions + an optional
     * {@code params_filter} over {@code trigger.data()}). The channel (the «how») is not part of this —
     * chat filtering is applied in {@code ChannelRouteResolver}.
     *
     * <p>Agents with no push transport (MCP) are dropped before ABAC: a binding makes their tools
     * reachable, not their attention, and a run nobody can receive would still cost a worker slot.
     */
    private List<Agent> findRecipients(UUID userId, Trigger trigger) {
        UUID connectionId = tryParseUuid(trigger.connectionId());
        if (connectionId == null) {
            log.warn("Trigger {} has non-UUID connectionId '{}' — no recipients",
                    trigger.name(), trigger.connectionId());
            return List.of();
        }
        List<Agent> bound = agentRepository.findBoundToConnection(userId, connectionId);
        List<Agent> targeted = TriggerAudience.filter(bound, audienceOf(trigger));
        return targeted.stream()
                .filter(agentDeliveryService::supportsPush)
                .filter(agent -> isTriggerAllowed(agent.getId(), connectionId, trigger))
                .toList();
    }

    private boolean isTriggerAllowed(UUID agentId, UUID connectionId, Trigger trigger) {
        AccessDecision decision = accessEvaluator.evaluate(
                agentId, connectionId, PolicyKind.TRIGGER, trigger.name());
        return decision.allowed()
                && InputFilterEvaluator.matches(decision.paramsFilter(), trigger.data());
    }

    private static UUID tryParseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The domain decision «how to deliver» for the recipients already selected
     * ({@link #findRecipients}), with no persistence — it delegates to {@link ChannelRouteResolver}.
     */
    private List<TriggerRoute> planRoutes(List<Agent> recipients, Trigger trigger) {
        List<TriggerRoute> routes = new ArrayList<>();
        for (Agent agent : recipients) {
            ChannelResolution resolution = channelRouteResolver.resolve(agent, trigger);
            switch (resolution.kind()) {
                case DIRECT -> routes.add(TriggerRoute.direct(agent));
                case CHANNEL -> routes.add(new TriggerRoute(agent, resolution.kind(),
                        resolution.channels(), resolution.message()));
                case CANCEL -> routes.add(new TriggerRoute(agent, resolution.kind(),
                        resolution.channels(), null));
                case SKIP -> log.debug("Channel filtered out trigger '{}' for agent {}",
                        trigger.name(), agent.getId());
            }
        }
        return routes;
    }

    /**
     * Persistence and delivery. It works with {@link TriggerLog}/{@link AgentRun}; the run's
     * {@code sessionId} is resolved here once (the channel's, otherwise the connection's) and
     * travels to the worker as the explicit field {@code AgentMessage.sessionId} — the rule is defined
     * on this side alone. A delivery failure for one recipient must not bring the others down — so we
     * isolate per route.
     */
    private void dispatch(TriggerLog triggerLog, Trigger trigger, List<TriggerRoute> routes) {
        if (routes.isEmpty()) {
            log.warn("Ignored trigger {} - {}", triggerLog.getConnectorCode(), triggerLog.getName());
            return;
        }
        // Guaranteed to parse: a trigger whose connection is not a UUID never reaches a recipient.
        UUID connectionId = tryParseUuid(triggerLog.getConnectionId());

        for (TriggerRoute route : routes) {
            try {
                if (route.kind() == ChannelResolution.Kind.CANCEL) {
                    stopConversation(triggerLog, route);
                    continue;
                }
                AgentRun agentRun = AgentRun.builder()
                        .triggerLog(triggerLog)
                        .agent(route.agent())
                        .destination(route.agent().getType().name())
                        .sessionId(runSessionId(triggerLog, route, connectionId))
                        // A snapshot of the route: GetRunContext (the profile and the inbound message) and
                        // SaveMessage delivery (stage 3) read the channels from here rather than re-resolving them.
                        .channels(ChannelsCodec.toMap(route.channels()))
                        .build();
                // Persist before delivery so the DB-generated id (the canonical run_id == DBOS
                // workflow id) is populated; delivery and the run registry rely on this id.
                agentRun = agentRunRepository.save(agentRun);
                if (route.sessionId() == null) {
                    warnIfQueueDeep(agentRun.getSessionId());
                }

                agentDeliveryService.deliverTrigger(agentRun, trigger, route.channels(), route.message());
                // After delivery: the file was in a conversation only if the message reached the agent.
                recordInboundFiles(agentRun, route);
            } catch (Exception e) {
                log.error("Failed to dispatch trigger '{}' to agent {}: {}",
                        trigger.name(), route.agent().getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * The single funnel of everything incoming: only here is the recipient's session known, and only
     * here does a fan-out (one photo, several agents) turn into a row per conversation.
     */
    private void recordInboundFiles(AgentRun agentRun, TriggerRoute route) {
        if (route.message() == null || route.message().parts().isEmpty()) {
            return;
        }
        fileReferenceService.record(
                route.message().parts().stream().map(Part::storageRef).toList(),
                agentRun.getSessionId(), route.agent().getId(), FileReferenceKind.INBOUND);
    }

    /**
     * The run's session: the channel's when the route has one, otherwise the connection's. A run born
     * of another run keeps its parent's session and does not come through here
     * ({@code DetachedToolResultDelivery} sets it directly).
     */
    private UUID runSessionId(TriggerLog triggerLog, TriggerRoute route, UUID connectionId) {
        UUID channelSessionId = route.sessionId();
        return channelSessionId != null
                ? channelSessionId
                : sessionResolver.forConnection(route.agent().getId(), triggerLog.getUserId(),
                        triggerLog.getConnectorCode(), connectionId);
    }

    /**
     * A connection's events now execute one after another, so a storm queues instead of running in
     * parallel and steering absorbs at most a few batches per run. The depth is only counted for
     * connection sessions — a conversation is paced by the human in it — and only to be seen in the
     * logs before it becomes an incident.
     */
    private void warnIfQueueDeep(UUID sessionId) {
        long enqueued = agentRunRepository.countEnqueuedBySession(sessionId);
        if (enqueued >= QUEUE_DEPTH_WARN) {
            log.warn("session {} has {} runs waiting: events arrive faster than the agent answers",
                    sessionId, enqueued);
        }
    }

    /**
     * The stop command: no run, no history row, no worker slot — it was addressed to the platform, not
     * to the agent. A reply goes out only when nothing was stopped; otherwise the stopped run's own
     * «stopped, managed to run …» is the answer, and it carries more than an acknowledgement would.
     */
    private void stopConversation(TriggerLog triggerLog, TriggerRoute route) {
        UUID sessionId = route.sessionId();
        int cancelled = sessionId != null
                ? runCancellationService.cancelSessionFromChannel(sessionId)
                : 0;
        if (cancelled > 0) {
            return;
        }
        ChannelInfo prompt = route.channels().prompt();
        outboundService.send(route.agent().getId(), prompt.channelId(), sessionId,
                OutboundMessage.text(channelTexts.get(ChannelTexts.NOTHING_TO_STOP,
                        "Nothing to stop: the agent is not working right now.")),
                triggerLog.getId() + ":nothing-to-stop", "answer", null);
    }

    private static TriggerAudience audienceOf(Trigger trigger) {
        return trigger.context() != null ? trigger.context().audience() : null;
    }

    /** A permitted route to an agent: {@code channels}/{@code message} == null for direct (non-channel) delivery. */
    private record TriggerRoute(Agent agent, ChannelResolution.Kind kind, Channels channels,
                                InboundMessage message) {
        private static TriggerRoute direct(Agent agent) {
            return new TriggerRoute(agent, ChannelResolution.Kind.DIRECT, null, null);
        }

        /**
         * The route's channel session, or null when the trigger came without a channel. Not the
         * run's session any more — see {@code runSessionId} — but still what the stop command
         * cancels by: it was addressed to the conversation, not to the agent's background work.
         */
        private UUID sessionId() {
            return Channels.sessionIdOf(channels);
        }
    }
}
