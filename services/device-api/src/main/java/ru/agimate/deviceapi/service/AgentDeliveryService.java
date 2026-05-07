package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;
import ru.agimate.deviceapi.service.dto.IToolResult;
import ru.agimate.deviceapi.service.trigger.TriggerMapper;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentDeliveryService {

    private final CentrifugoService centrifugoService;
    private final WebhookDeliveryService webhookDeliveryService;
    private final DbosDeliveryService dbosDeliveryService;
    private final AgentService agentService;

    public void deliverTrigger(Agent agent, TriggerLogAgent triggerLogAgent) {
        try {
            switch (agent.getType()) {
                case CENTRIFUGO -> sendTriggerToCentrifugo(agent, triggerLogAgent);
                case WEBHOOK -> sendTriggerToWebhook(agent, triggerLogAgent);
                case GENERIC -> sendTriggerToGeneric(agent, triggerLogAgent);
            }
        } catch (Exception e) {
            triggerLogAgent.setError(e.getMessage());
            log.warn("Failed to send trigger '{}' to agent '{}' via {}: {}",
                    triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId(), agent.getType(), e.getMessage());
        }
    }

    public void deliverToolResult(UUID agentPubId, IToolResult toolResult) {
        Agent agent = agentService.findByPubId(agentPubId);
        try {
            switch (agent.getType()) {
                case CENTRIFUGO -> sendToolResultToCentrifugo(agent, toolResult);
                case WEBHOOK -> sendToolResultToWebhook(agent, toolResult);
                case GENERIC -> sendToolResultToGeneric(agent, toolResult);
            }
        } catch (Exception e) {
            log.warn("Failed to deliver tool result '{}' to agent '{}' via {}: {}",
                    toolResult.getId(), agent.getPubId(), agent.getType(), e.getMessage());
        }
    }

    private void sendTriggerToCentrifugo(Agent agent, TriggerLogAgent triggerLogAgent) {
        centrifugoService.publishMessage(
                "agent:" + agent.getPubId(),
                "trigger",
                TriggerMapper.map(triggerLogAgent.getTriggerLog())
        );
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo", triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId());
    }

    private void sendTriggerToWebhook(Agent agent, TriggerLogAgent triggerLogAgent) {
        webhookDeliveryService.deliverWebhook(agent, triggerLogAgent);
        log.debug("Trigger '{}' sent to agent '{}' via webhook", triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId());
    }

    private void sendToolResultToCentrifugo(Agent agent, IToolResult toolResult) {
        centrifugoService.publishMessage("agent:" + agent.getPubId(), "toolResult", toolResult);
        log.debug("Tool result '{}' sent to agent '{}' via centrifugo", toolResult.getId(), agent.getPubId());
    }

    private void sendToolResultToWebhook(Agent agent, IToolResult toolResult) {
//         disabled temporary
//        webhookDeliveryService.deliverToolResult(agent, toolResult);
        log.warn("Tool result '{}' do not sent to '{}' via webhook - it's disabled", toolResult.getId(), agent.getPubId());
    }

    private void sendTriggerToGeneric(Agent agent, TriggerLogAgent triggerLogAgent) {
        dbosDeliveryService.deliverTrigger(agent, triggerLogAgent);
        log.debug("Trigger '{}' sent to agent '{}' via generic (DBOS)",
                triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId());
    }

    private void sendToolResultToGeneric(Agent agent, IToolResult toolResult) {
        log.warn("Tool result '{}' do not sent to '{}' via generic (DBOS) - it's disabled",
                toolResult.getId(), agent.getPubId());
    }
}
