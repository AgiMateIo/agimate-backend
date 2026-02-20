package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.app.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.AgentSettings;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.database.repositories.AgentSettingsRepository;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final AgentSettingsRepository agentSettingsRepository;
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

            List<AgentSettings> agents = agentSettingsRepository
                    .findRoutableByUserPubIdAndTriggerName(userPubId, triggerRequest.name());

            for (AgentSettings settings : agents) {
                TriggerLogAgent triggerLogAgent = TriggerLogAgent.builder()
                        .triggerLog(triggerLog)
                        .agentSettings(settings)
                        .routedTo(settings.getTriggersTo())
                        .build();
                triggerLog.getTriggerLogAgents().add(triggerLogAgent);
            }

            // Save first so TriggerLogAgent entities get IDs (needed for webhook delivery FK)
            triggerLogRepository.save(triggerLog);

            // Now dispatch async deliveries
            for (TriggerLogAgent triggerLogAgent : triggerLog.getTriggerLogAgents()) {
                AgentSettings settings = triggerLogAgent.getAgentSettings();
                switch (settings.getTriggersTo()) {
                    case "centrifugo" -> routeToCentrifugo(settings, triggerRequest);
                    case "webhook" -> webhookDeliveryService.deliverWebhook(settings, triggerLogAgent, app, triggerRequest);
                    default -> log.warn("Unknown triggersTo value '{}' for agent '{}'", settings.getTriggersTo(), settings.getApiKeyPubId());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}': {}", triggerRequest.name(), e.getMessage());
        }
    }

    private void routeToCentrifugo(AgentSettings settings, TriggerRequest triggerRequest) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "trigger");
            payload.put("triggerName", triggerRequest.name());
            payload.put("triggerData", triggerRequest.data());
            payload.put("deviceId", triggerRequest.deviceId());
            payload.put("occurredAt", triggerRequest.occurredAt());

            String channel = "agent:" + settings.getApiKeyPubId();
            centrifugoService.publishMessage(channel, payload);
            log.debug("Routed trigger '{}' to agent channel '{}'", triggerRequest.name(), channel);
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}' to centrifugo for agent '{}': {}",
                    triggerRequest.name(), settings.getApiKeyPubId(), e.getMessage());
        }
    }
}
