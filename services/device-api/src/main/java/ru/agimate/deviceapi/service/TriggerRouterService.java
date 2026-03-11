package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.abac.AgentTriggerPolicyService;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.*;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final CentrifugoService centrifugoService;
    private final TriggerLogService triggerLogService;
    private final TriggerLogRepository triggerLogRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final AgentTriggerPolicyService agentTriggerPolicyService;

    @Async
    public void routeAppTrigger(App app, TriggerRequest triggerRequest) {
        routeTrigger(app.getUserPubId(), app.getConnectorCode(), app.getPubId().toString(), triggerRequest);
    }

    @Async
    public void routeWhTrigger(IntegrationCredentials integration, TriggerRequest triggerRequest) {
        routeTrigger(integration.getUserPubId(), integration.getConnectorCode(), integration.getPubId().toString(), triggerRequest);
    }

    private void routeTrigger(UUID userPubId, String connectorCode, String identity, TriggerRequest triggerRequest) {
        TriggerLog triggerLog = triggerLogService.createTriggerLog(userPubId, connectorCode, identity, triggerRequest);

        List<Agent> agents = agentTriggerPolicyService.findAllowedAgents(userPubId, connectorCode, identity, triggerRequest.name());

        for (Agent agent : agents) {
            TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                    .triggerLog(triggerLog)
                    .agent(agent)
                    .destination(agent.getTriggerDestination().name())
                    .build();
            fireTrigger(triggerLogAgent, triggerRequest);
            triggerLog.getTriggerLogAgents().add(triggerLogAgent);
        }
        triggerLogRepository.save(triggerLog);
    }

    private void fireTrigger(TriggerLogAgent triggerLogAgent, TriggerRequest triggerRequest) {
        Agent agent = triggerLogAgent.getAgent();
        try {
            switch (agent.getTriggerDestination()) {
                case CENTRIFUGO -> deliverToCentrifugo(triggerLogAgent, triggerRequest);
                case WEBHOOK -> webhookDeliveryService.deliverWebhook(agent, triggerLogAgent, triggerRequest);
            }
        } catch (Exception e) {
            triggerLogAgent.setError(e.getMessage());
            log.warn("Failed to route trigger '{}' to agent '{}': {}", triggerRequest.name(), agent.getPubId(), e.getMessage());
        }
    }

    public record Trigger(
            String connectorCode,
            String identity,
            String triggerId,
            String triggerName,
            String occurredAt,
            Map<String, Object> triggerInput
    ) {}

    private void deliverToCentrifugo(TriggerLogAgent triggerLogAgent, TriggerRequest triggerRequest) {
        Agent agent = triggerLogAgent.getAgent();
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "trigger");
        TriggerLog triggerLog = triggerLogAgent.getTriggerLog();
        payload.put("payload", new Trigger(
                triggerLog.getConnectorCode(),
                triggerLog.getIdentity(),
                triggerLog.getTriggerId(),
                triggerLog.getTriggerName(),
                triggerRequest.occurredAt() != null ? triggerRequest.occurredAt().toString() : null,
                triggerLog.getTriggerInput()
                )
        );

        centrifugoService.publishMessage("agent:" + agent.getPubId(), payload);
        log.debug("Routed trigger '{}' to agent channel '{}'", triggerRequest.name(), agent.getPubId());
    }
}
