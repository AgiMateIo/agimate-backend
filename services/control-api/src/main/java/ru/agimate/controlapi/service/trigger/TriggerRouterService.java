package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AgentTriggerPolicyService;
import ru.agimate.controlapi.controller.app.dto.TriggerRequest;
import ru.agimate.controlapi.database.entities.*;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final TriggerLogService triggerLogService;
    private final TriggerLogProbeService triggerLogProbeService;
    private final AgentTriggerPolicyService agentTriggerPolicyService;
    private final AgentDeliveryService agentDeliveryService;
    private final ChannelRouteResolver channelRouteResolver;

    private final TriggerLogAgentRepository triggerLogAgentRepository;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        Trigger trigger = Trigger.createBasic(
                app.getConnectorCode(),
                app.getId().toString(),
                triggerRequest.name(),
                JsonUtils.fromJsonToMap(triggerRequest.data().toString())
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
     * Получатели триггера («кто»): грубый ABAC по identity ({@code findAllowedAgents}),
     * сужение по {@link TriggerAudience} и тонкий per-agent input_filter по {@code trigger.data()}
     * ({@code selectMatchingAllowPolicy}). Канал ({@code policy.channelId}) здесь не при чём — это слой «как».
     */
    private List<Agent> findRecipients(UUID userId, Trigger trigger) {
        List<Agent> allowed = agentTriggerPolicyService.findAllowedAgents(
                userId, trigger.connectorCode(), trigger.identity(), trigger.name());
        List<Agent> targeted = TriggerAudience.filter(allowed, audienceOf(trigger));
        return targeted.stream()
                .filter(agent -> agentTriggerPolicyService.selectMatchingAllowPolicy(
                        agent.getId(), trigger.connectorCode(), trigger.identity(),
                        trigger.name(), trigger.data()).isPresent())
                .toList();
    }

    /**
     * Доменное решение «как доставлять» для уже отобранных получателей ({@link #findRecipients}),
     * без персистентности — делегирует {@link ChannelRouteResolver}.
     */
    private List<TriggerRoute> planRoutes(List<Agent> recipients, Trigger trigger) {
        List<TriggerRoute> routes = new ArrayList<>();
        for (Agent agent : recipients) {
            ChannelResolution resolution = channelRouteResolver.resolve(agent, trigger);
            switch (resolution.kind()) {
                case DIRECT -> routes.add(TriggerRoute.direct(agent));
                case CHANNEL -> routes.add(new TriggerRoute(agent, resolution.channels(), resolution.message()));
                case SKIP -> log.debug("Channel filtered out trigger '{}' for agent {}",
                        trigger.name(), agent.getId());
            }
        }
        return routes;
    }

    /**
     * Персистентность и доставка. Работает с {@link TriggerLog}/{@link TriggerLogAgent};
     * {@code sessionId} запуска берётся из prompt-канала. Сбой доставки одного получателя
     * не должен ронять остальных — изолируем по маршруту.
     */
    private void dispatch(TriggerLog triggerLog, Trigger trigger, List<TriggerRoute> routes) {
        if (routes.isEmpty()) {
            log.warn("Ignored trigger {} - {}", triggerLog.getConnectorCode(), triggerLog.getName());
            return;
        }

        for (TriggerRoute route : routes) {
            try {
                TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                        .triggerLog(triggerLog)
                        .agent(route.agent())
                        .destination(route.agent().getType().name())
                        .sessionId(route.promptSessionId())
                        .build();
                // Persist before delivery so the DB-generated id (the canonical run_id == DBOS
                // workflow id) is populated; delivery and the run registry rely on this id.
                triggerLogAgent = triggerLogAgentRepository.save(triggerLogAgent);

                agentDeliveryService.deliverTrigger(triggerLogAgent, trigger, route.channels(), route.message());
            } catch (Exception e) {
                log.error("Failed to dispatch trigger '{}' to agent {}: {}",
                        trigger.name(), route.agent().getId(), e.getMessage(), e);
            }
        }
    }

    private static TriggerAudience audienceOf(Trigger trigger) {
        return trigger.context() != null ? trigger.context().audience() : null;
    }

    /** Разрешённый маршрут до агента: {@code channels}/{@code message} == null для прямой (не канальной) доставки. */
    private record TriggerRoute(Agent agent, Channels channels, InboundMessage message) {
        private static TriggerRoute direct(Agent agent) {
            return new TriggerRoute(agent, null, null);
        }

        /** sessionId prompt-канала — single-writer ключ запуска; null для прямой доставки. */
        private UUID promptSessionId() {
            return channels != null && channels.prompt() != null ? channels.prompt().sessionId() : null;
        }
    }
}
