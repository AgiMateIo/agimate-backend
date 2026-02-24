package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final AgentRepository agentRepository;
    private final CentrifugoService centrifugoService;
    private final TriggerLogService triggerLogService;
    private final TriggerLogRepository triggerLogRepository;
    private final WebhookDeliveryService webhookDeliveryService;

    @Async
    public void routeTrigger(App app, TriggerRequest triggerRequest) {
        TriggerLog.TriggerLogBuilder triggerLogBuilder = triggerLogService.getTriggerLogBuilder(app, triggerRequest);
        TriggerLog triggerLog = triggerLogService.logTrigger(triggerLogBuilder);
        try {
            UUID userPubId = app.getUserPubId();

            List<Agent> agents = agentRepository
                    .findRoutableByUserPubIdAndTriggerName(userPubId, triggerRequest.name());

            for (Agent agent : agents) {
                TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                        .triggerLog(triggerLog)
                        .agent(agent)
                        .routedTo(agent.getTriggersTo())
                        .build();
                triggerLog.getTriggerLogAgents().add(triggerLogAgent);
            }

            // Save first so TriggerLogAgent entities get IDs (needed for webhook delivery FK)
            triggerLogRepository.save(triggerLog);

            // Now dispatch async deliveries
            for (TriggerLogAgent triggerLogAgent : triggerLog.getTriggerLogAgents()) {
                Agent agent = triggerLogAgent.getAgent();
                switch (agent.getTriggersTo()) {
                    case "centrifugo" -> routeToCentrifugo(agent, triggerRequest);
                    case "webhook" -> webhookDeliveryService.deliverWebhook(agent, triggerLogAgent, app, triggerRequest);
                    default -> log.warn("Unknown triggersTo value '{}' for agent '{}'", agent.getTriggersTo(), agent.getApiKeyPubId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}': {}", triggerRequest.name(), e.getMessage());
        }
    }

    private void routeToCentrifugo(Agent agent, TriggerRequest triggerRequest) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "trigger");
            payload.put("triggerName", triggerRequest.name());
            payload.put("triggerData", triggerRequest.data());
            payload.put("deviceId", triggerRequest.deviceId());
            payload.put("occurredAt", triggerRequest.occurredAt());

            String channel = "agent:" + agent.getApiKeyPubId();
            centrifugoService.publishMessage(channel, payload);
            log.debug("Routed trigger '{}' to agent channel '{}'", triggerRequest.name(), channel);
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}' to centrifugo for agent '{}': {}",
                    triggerRequest.name(), agent.getApiKeyPubId(), e.getMessage());
        }
    }
}
