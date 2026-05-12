package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.abac.AgentTriggerPolicyService;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.*;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.deviceapi.database.repositories.ChannelRepository;
import ru.agimate.deviceapi.database.repositories.TriggerLogAgentRepository;
import ru.agimate.deviceapi.service.AgentDeliveryService;
import ru.agimate.deviceapi.service.channel.ChannelMessageInboundService;
import ru.agimate.deviceapi.service.channel.InputFilterEvaluator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final TriggerLogService triggerLogService;
    private final AgentTriggerPolicyService agentTriggerPolicyService;
    private final AgentDeliveryService agentDeliveryService;

    private final TriggerLogAgentRepository triggerLogAgentRepository;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final ChannelRepository channelRepository;
    private final ChannelMessageInboundService channelMessageInboundService;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        Trigger trigger = Trigger.createBasic(
                app.getConnectorCode(),
                app.getPubId().toString(),
                triggerRequest.name(),
                JsonUtils.fromJsonToMap(triggerRequest.data().toString())
        );
        routeTrigger(app.getUserPubId(), trigger);
    }

    @Async
    public void routeWhTrigger(IntegrationCredentials integration, Trigger trigger) {
        routeTrigger(integration.getUserPubId(), trigger);
    }


    public void routeInternalTrigger(UUID userPubId, UUID agenticTeamPubId, Trigger trigger) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, trigger);

        if (isBlockingProbe(trigger.data())) {
            log.info("Internal trigger contains discovery probe (block mode) - skipping agent routing for user={}", userPubId);
            triggerLogService.save(triggerLog);
            return;
        }

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgentsForTeamId(
                userPubId, agenticTeamPubId,  trigger.connectorCode(), trigger.identity(), trigger.name());

        sendTrigger(agents, triggerLog, trigger);

        triggerLogService.save(triggerLog);

    }

    private void routeTrigger(UUID userPubId, Trigger trigger) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, trigger);

        if (isBlockingProbe(trigger.data())) {
            log.info("Trigger contains discovery probe (block mode) - skipping agent routing for user={}", userPubId);
            triggerLogService.save(triggerLog);
            return;
        }

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgents(
                userPubId, trigger.connectorCode(), trigger.identity(), trigger.name());

        sendTrigger(agents, triggerLog, trigger);

        triggerLogService.save(triggerLog);
    }

    private boolean isBlockingProbe(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return JsonUtils.toJson(data)
                .map(s -> s.contains("agm-probe-block-"))
                .orElse(false);
    }

    private void sendTrigger(List<Agent> agents, TriggerLog triggerLog, Trigger trigger) {
        if (agents.isEmpty()) {
            log.warn("Ignored trigger {} - {}", triggerLog.getConnectorCode(), triggerLog.getTriggerName());
        }

        for (Agent agent : agents) {
            Optional<AgentTriggerPolicy> bestPolicy = selectMatchingAllowPolicy(agent, trigger);
            if (bestPolicy.isEmpty()) {
                log.debug("Skipping agent {} - no policy matched after input_filter check", agent.getPubId());
                continue;
            }

            ChannelContext channelContext = null;
            AgentTriggerPolicy policy = bestPolicy.get();
            if (policy.getChannelId() != null) {
                channelContext = processChannelInbound(policy.getChannelId(), trigger);
            }

            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getType().name())
                    .build();

            agentDeliveryService.deliverTrigger(agent, triggerLogAgent, channelContext);

            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
    }

    private Optional<AgentTriggerPolicy> selectMatchingAllowPolicy(Agent agent, Trigger trigger) {
        List<AgentTriggerPolicy> matching = agentTriggerPolicyRepository.findMatchingPolicies(
                agent.getPubId(), trigger.connectorCode(), trigger.identity(), trigger.name());

        return matching.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .filter(p -> InputFilterEvaluator.matches(p.getInputFilter(), trigger.data()))
                .max(Comparator.comparingInt(this::specificity));
    }

    private int specificity(AgentTriggerPolicy policy) {
        if (policy.getPriority() != null) {
            return policy.getPriority();
        }
        int spec = 0;
        if (policy.getConnectorCode() != null) spec++;
        if (policy.getConnectorIdentity() != null) spec++;
        if (policy.getTriggerName() != null) spec++;
        if (policy.getInputFilter() != null && !policy.getInputFilter().isEmpty()) spec++;
        return spec;
    }

    private ChannelContext processChannelInbound(Long channelId, Trigger trigger) {
        Channel channel = channelRepository.findById(channelId).orElse(null);
        if (channel == null || channel.getDeletedAt() != null) {
            log.warn("Channel id={} not found or deleted; treating as no-channel route", channelId);
            return null;
        }
        var inboundResult = channelMessageInboundService.process(channel, trigger);
        return new ChannelContext(channel.getPubId(), inboundResult.session().getPubId());
    }

}
