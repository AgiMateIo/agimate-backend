package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.device.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.AgentSettings;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
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
    private final TriggerNotificationService triggerNotificationService;

    @Async
    public void routeTrigger(DeviceAuthKey deviceAuthKey, TriggerRequest triggerRequest) {
        TriggerLog.TriggerLogBuilder triggerLogBuilder = triggerLogService.getTriggerLogBuilder(deviceAuthKey, triggerRequest);
        TriggerLog triggerLog = triggerLogService.logTrigger(triggerLogBuilder);
        try {
            UUID userPubId = deviceAuthKey.getUserPubId();

            List<AgentSettings> agents = agentSettingsRepository
                    .findRoutableByUserPubIdAndTriggerName(userPubId, triggerRequest.name());

            for (AgentSettings settings : agents) {
                triggerLog.getTriggerLogAgents().add(
                        TriggerLogAgent.builder()
                                .triggerLog(triggerLog)
                                .agentSettings(settings)
                                .routedTo(settings.getTriggersTo())
                                .build()
                );
                switch (settings.getTriggersTo()) {
                    case "centrifugo" -> routeToCentrifugo(settings, triggerRequest);
                    case "webhook" -> routeToWebhook(deviceAuthKey, triggerRequest);
                    default -> log.warn("Unknown triggersTo value '{}' for agent '{}'", settings.getTriggersTo(), settings.getApiKeyPubId());
                }
            }

            triggerLogRepository.save(triggerLog);
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

    private void routeToWebhook(DeviceAuthKey deviceAuthKey, TriggerRequest triggerRequest) {
        try {
            triggerNotificationService.notifyTrigger(deviceAuthKey, triggerRequest);
            log.debug("Routed trigger '{}' to webhook", triggerRequest.name());
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}' to webhook: {}",
                    triggerRequest.name(), e.getMessage());
        }
    }
}
