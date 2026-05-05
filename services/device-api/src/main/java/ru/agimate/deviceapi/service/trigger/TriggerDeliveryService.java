package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.service.centrifugo.CentrifugoService;
import ru.agimate.deviceapi.service.WebhookDeliveryService;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerDeliveryService {

    private final CentrifugoService centrifugoService;
    private final WebhookDeliveryService webhookDeliveryService;

    public void fireTrigger(Agent agent, TriggerLogAgent triggerLogAgent) {
        try {
            switch (agent.getTriggerDestination()) {
                case CENTRIFUGO -> sendToCentrifugo(agent, triggerLogAgent);
                case WEBHOOK -> sendToWebhook(agent, triggerLogAgent);
            }
        } catch (Exception e) {
            triggerLogAgent.setError(e.getMessage());
            log.warn("Failed to send trigger '{}' to agent '{}' via {}: {}",
                    triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId(), agent.getTriggerDestination(), e.getMessage());
        }
    }

    private void sendToCentrifugo(Agent agent, TriggerLogAgent triggerLogAgent) {

        centrifugoService.publishMessage(
                "agent:" + agent.getPubId(),
                "trigger",
                TriggerMapper.map(triggerLogAgent.getTriggerLog())
        );
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo", triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId());
    }

    private void sendToWebhook(Agent agent, TriggerLogAgent triggerLogAgent) {
        webhookDeliveryService.deliverWebhook(agent, triggerLogAgent);
        log.debug("Trigger '{}' sent to agent '{}' via webhook", triggerLogAgent.getTriggerLog().getTriggerName(), agent.getPubId());
    }
}
