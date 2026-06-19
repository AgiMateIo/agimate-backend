package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.abac.AgentTriggerPolicyService;
import ru.agimate.controlapi.controller.app.dto.TriggerRequest;
import ru.agimate.controlapi.database.entities.*;
import ru.agimate.controlapi.database.repositories.ChannelRepository;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.controlapi.service.AgentDeliveryService;
import ru.agimate.controlapi.service.channel.ChannelSessionService;
import ru.agimate.controlapi.service.channel.handler.dto.ChannelConfig;
import ru.agimate.controlapi.service.channel.handler.ChannelHandler;
import ru.agimate.controlapi.service.channel.handler.ChannelHandlerRegistry;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final TriggerLogService triggerLogService;
    private final TriggerLogProbeService triggerLogProbeService;
    private final AgentTriggerPolicyService agentTriggerPolicyService;
    private final AgentDeliveryService agentDeliveryService;

    private final TriggerLogAgentRepository triggerLogAgentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelSessionService channelSessionService;
    private final ChannelHandlerRegistry channelHandlerRegistry;

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

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgents(
                userId, trigger.connectorCode(), trigger.identity(), trigger.name());
        agents = TriggerAudience.filter(agents, trigger.audience());

        List<AgentRoute> routes = planRoutes(agents, trigger);
        dispatch(triggerLog, trigger, routes);
    }

    /**
     * Фаза 1 — доменное решение: кому и как доставлять. Работает только с {@link Trigger}, без персистентности.
     * Policy (ABAC) решает, что агенту разрешено; наличие канала для {@code (agent, connector, identity)}
     * решает, как строится взаимодействие — это разные слои, поэтому {@code policy.channelId} здесь не читается.
     */
    private List<AgentRoute> planRoutes(List<Agent> agents, Trigger trigger) {
        List<AgentRoute> routes = new ArrayList<>();
        for (Agent agent : agents) {
            Optional<AgentTriggerPolicy> bestPolicy = agentTriggerPolicyService.selectMatchingAllowPolicy(
                    agent.getId(), trigger.connectorCode(), trigger.identity(), trigger.name(), trigger.data());
            if (bestPolicy.isEmpty()) {
                log.debug("Skipping agent {} - no policy matched after input_filter check", agent.getId());
                continue;
            }

            Channel channel = channelRepository.findByAgentIdAndConnectorCodeAndIdentityAndDeletedAtIsNull(
                    agent.getId(), trigger.connectorCode(), trigger.identity()).orElse(null);
            if (channel == null) {
                routes.add(AgentRoute.direct(agent));
                continue;
            }

            ChannelInbound inbound = resolveChannelInbound(channel, trigger);
            if (inbound.skip()) {
                log.debug("Channel {} filtered out trigger '{}' for agent {}",
                        channel.getId(), trigger.name(), agent.getId());
                continue;
            }
            routes.add(new AgentRoute(agent, inbound.channels(), inbound.message()));
        }
        return routes;
    }

    /**
     * Фаза 2 — персистентность и доставка. Работает с {@link TriggerLog}/{@link TriggerLogAgent};
     * {@code sessionId} запуска берётся из prompt-канала.
     */
    private void dispatch(TriggerLog triggerLog, Trigger trigger, List<AgentRoute> routes) {
        if (routes.isEmpty()) {
            log.warn("Ignored trigger {} - {}", triggerLog.getConnectorCode(), triggerLog.getName());
            return;
        }

        for (AgentRoute route : routes) {
            UUID sessionId = route.channels() != null && route.channels().prompt() != null
                    ? route.channels().prompt().sessionId() : null;

            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(route.agent())
                    .destination(route.agent().getType().name())
                    .sessionId(sessionId)
                    .build();
            // Persist before delivery so the DB-generated id (the canonical run_id == DBOS
            // workflow id) is populated; delivery and the run registry rely on this id.
            triggerLogAgent = triggerLogAgentRepository.save(triggerLogAgent);

            agentDeliveryService.deliverTrigger(triggerLogAgent, trigger, route.channels(), route.message());

            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
    }

    /** Разрешённый маршрут до агента: {@code channels}/{@code message} == null для прямой (не канальной) доставки. */
    private record AgentRoute(Agent agent, Channels channels, InboundMessage message) {
        private static AgentRoute direct(Agent agent) {
            return new AgentRoute(agent, null, null);
        }
    }

    /**
     * @param skip     true → handler-фильтр отверг триггер: доставку пропустить (сессия не создаётся)
     * @param channels каналы взаимодействия (prompt заполнен); null при отсутствии handler'а
     * @param message  извлечённое входящее сообщение; null при отсутствии handler'а
     */
    private record ChannelInbound(boolean skip, Channels channels, InboundMessage message) {
        private static final ChannelInbound SKIP = new ChannelInbound(true, null, null);

        private static ChannelInbound deliver(Channels channels, InboundMessage message) {
            return new ChannelInbound(false, channels, message);
        }
    }

    private ChannelInbound resolveChannelInbound(Channel channel, Trigger trigger) {
        ChannelHandler handler = channelHandlerRegistry.find(channel.getChannelHandler()).orElse(null);
        if (handler == null) {
            log.warn("No handler '{}' for channel {}; treating as direct route",
                    channel.getChannelHandler(), channel.getId());
            return ChannelInbound.deliver(null, null);
        }

        // Извлечение текста выполняет control-api для всех handler'ов (generic делает JSON-фолбэк);
        // empty == «триггер не для этого канала» (фильтр) → доставку пропускаем.
        ChannelConfig cc = new ChannelConfig(
                channel.getAgentId(), channel.getConnectorCode(), channel.getIdentity(), channel.getConfig());
        Optional<InboundMessage> inbound = handler.handleInput(cc, trigger);
        if (inbound.isEmpty()) {
            return ChannelInbound.SKIP;
        }

        ChannelSession session = channelSessionService.findOrCreateActive(channel, null);
        ChannelInfo prompt = new ChannelInfo(channel.getId(), session.getId(), null);
        return ChannelInbound.deliver(Channels.ofPrompt(prompt), inbound.get());
    }

}
