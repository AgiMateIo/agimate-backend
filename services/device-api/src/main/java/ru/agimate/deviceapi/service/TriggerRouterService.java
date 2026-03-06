package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.abac.AccessDecision;
import ru.agimate.deviceapi.abac.TriggerPolicyEvaluatorService;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final AgentRepository agentRepository;
    private final CentrifugoService centrifugoService;
    private final TriggerLogService triggerLogService;
    private final TriggerLogRepository triggerLogRepository;
    private final WebhookDeliveryService webhookDeliveryService;
    private final TriggerPolicyEvaluatorService triggerPolicyEvaluatorService;

    @Async
    public void routeTrigger(App app, TriggerRequest triggerRequest) {
        TriggerLog.TriggerLogBuilder triggerLogBuilder = triggerLogService.getTriggerLogBuilder(app, triggerRequest);
        TriggerLog triggerLog = triggerLogService.logTrigger(triggerLogBuilder);
        try {
            UUID userPubId = app.getUserPubId();

            List<Agent> agents = agentRepository
                    .findRoutableByUserPubIdAndTriggerName(userPubId, triggerRequest.name());

            String connectorCode = app.getPubId() != null ? app.getPubId().toString() : null;

            for (Agent agent : agents) {
                AccessDecision decision = triggerPolicyEvaluatorService.evaluate(
                        agent.getPubId(), connectorCode, null, triggerRequest.name());
                if (!decision.allowed()) {
                    log.debug("Skipping agent '{}' for trigger '{}': {}",
                            agent.getPubId(), triggerRequest.name(), decision.reason());
                    continue;
                }

                TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                        .triggerLog(triggerLog)
                        .agent(agent)
                        .routedTo(agent.getTriggerDestination().name())
                        .build();
                triggerLog.getTriggerLogAgents().add(triggerLogAgent);
            }

            // Save first so TriggerLogAgent entities get IDs (needed for webhook delivery FK)
            triggerLogRepository.save(triggerLog);

            // Now dispatch async deliveries
            for (TriggerLogAgent triggerLogAgent : triggerLog.getTriggerLogAgents()) {
                Agent agent = triggerLogAgent.getAgent();
                switch (agent.getTriggerDestination()) {
                    case CENTRIFUGO -> routeToCentrifugo(agent, triggerRequest);
                    case WEBHOOK -> webhookDeliveryService.deliverWebhook(agent, triggerLogAgent, app, triggerRequest);
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

            String channel = "agent:" + agent.getPubId();
            centrifugoService.publishMessage(channel, payload);
            log.debug("Routed trigger '{}' to agent channel '{}'", triggerRequest.name(), channel);
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}' to centrifugo for agent '{}': {}",
                    triggerRequest.name(), agent.getPubId(), e.getMessage());
        }
    }
}
