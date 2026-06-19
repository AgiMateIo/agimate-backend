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

import java.util.List;
import java.util.Map;
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

        sendTrigger(agents, triggerLog, trigger);
    }

    private void sendTrigger(List<Agent> agents, TriggerLog triggerLog, Trigger trigger) {
        if (agents.isEmpty()) {
            log.warn("Ignored trigger {} - {}", triggerLog.getConnectorCode(), triggerLog.getName());
        }

        for (Agent agent : agents) {
            Optional<AgentTriggerPolicy> bestPolicy = agentTriggerPolicyService.selectMatchingAllowPolicy(
                    agent.getId(), trigger.connectorCode(), trigger.identity(), trigger.name(), trigger.data());
            if (bestPolicy.isEmpty()) {
                log.debug("Skipping agent {} - no policy matched after input_filter check", agent.getId());
                continue;
            }

            AgentTriggerPolicy policy = bestPolicy.get();
            ChannelContext channelContext = null;
            if (policy.getChannelId() != null) {
                ChannelInbound inbound = resolveChannelInbound(policy.getChannelId(), trigger);
                if (inbound.skip()) {
                    log.debug("Channel {} filtered out trigger '{}' for agent {}",
                            policy.getChannelId(), trigger.name(), agent.getId());
                    continue;
                }
                channelContext = inbound.context();
            }

            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getType().name())
                    .sessionId(channelContext != null ? channelContext.channelSessionId() : null)
                    .build();
            // Persist before delivery so the DB-generated id (the canonical run_id == DBOS
            // workflow id) is populated; delivery and the run registry rely on this id.
            triggerLogAgent = triggerLogAgentRepository.save(triggerLogAgent);

            agentDeliveryService.deliverTrigger(agent, triggerLogAgent, channelContext);

            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
    }

    /**
     * @param skip    true → handler-фильтр отверг триггер: доставку пропустить (сессия не создаётся)
     * @param context контекст доставки; null при отсутствующем/удалённом канале (обычная доставка триггера)
     */
    private record ChannelInbound(boolean skip, ChannelContext context) {
        private static final ChannelInbound SKIP = new ChannelInbound(true, null);

        private static ChannelInbound deliver(ChannelContext context) {
            return new ChannelInbound(false, context);
        }
    }

    private ChannelInbound resolveChannelInbound(UUID channelId, Trigger trigger) {
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || channel.getDeletedAt() != null) {
            log.warn("Channel id={} not found or deleted; treating as no-channel route", channelId);
            return ChannelInbound.deliver(null);
        }

        Map<String, Object> config = channel.getConfig();
        Object rawMessageField = config != null ? config.get("messageField") : null;
        String messageField = rawMessageField != null ? rawMessageField.toString() : null;

        // Handlers без messageField в config (например telegram) извлекают текст сами через handleInput();
        // handleInput() == empty означает «триггер не для этого канала» (фильтр) → доставку пропускаем.
        // generic оставляет messageField — текст по-прежнему извлекает воркер (поведение не меняется).
        String inboundText = null;
        if (messageField == null) {
            ChannelHandler handler = channelHandlerRegistry.find(channel.getChannelHandler()).orElse(null);
            if (handler != null) {
                ChannelConfig cc = new ChannelConfig(channel.getConnectorCode(), channel.getIdentity(), config);
                Optional<InboundMessage> inbound = handler.handleInput(cc, trigger);
                if (inbound.isEmpty()) {
                    return ChannelInbound.SKIP;
                }
                inboundText = inbound.get().text();
            }
        }

        ChannelSession session = channelSessionService.findOrCreateActive(channel, null);
        return ChannelInbound.deliver(new ChannelContext(
                channel.getId(),
                session.getId(),
                channel.getName(),
                messageField,
                inboundText
        ));
    }

}
