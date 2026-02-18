package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.deviceapi.controller.device.dto.TriggerRequest;
import ru.agimate.deviceapi.database.entities.AgentSettings;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;
import ru.agimate.deviceapi.database.entities.TriggerLog;
import ru.agimate.deviceapi.database.repositories.AgentSettingsRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerRepository;
import ru.agimate.deviceapi.database.repositories.TriggerLogRepository;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerRouterService {

    private final AgentSettingsRepository agentSettingsRepository;
    private final AgentTriggerRepository agentTriggerRepository;
    private final CentrifugoService centrifugoService;
    private final TriggerLogRepository triggerLogRepository;

    @Async
    public void routeTriggerToAgents(DeviceAuthKey deviceAuthKey, TriggerRequest triggerRequest, TriggerLog triggerLog) {
        try {
            List<AgentSettings> allSettings = agentSettingsRepository.findAll();
            Set<String> routedMethods = new LinkedHashSet<>();

            for (AgentSettings settings : allSettings) {
                if ("ignore".equals(settings.getTriggersTo())) {
                    continue;
                }

                boolean subscribed = settings.isTriggersAllowAll()
                        || agentTriggerRepository.existsByApiKeyPubIdAndTriggerName(
                                settings.getApiKeyPubId(), triggerRequest.name());

                if (!subscribed) {
                    continue;
                }

                try {
                    switch (settings.getTriggersTo()) {
                        case "centrifugo" -> {
                            Map<String, Object> payload = new HashMap<>();
                            payload.put("type", "trigger");
                            payload.put("triggerName", triggerRequest.name());
                            payload.put("triggerData", triggerRequest.data());
                            payload.put("deviceId", triggerRequest.deviceId());
                            payload.put("occurredAt", triggerRequest.occurredAt());

                            String channel = "agent:" + settings.getApiKeyPubId();
                            centrifugoService.publishMessage(channel, payload);
                            routedMethods.add("centrifugo");
                            log.debug("Routed trigger '{}' to agent channel '{}'", triggerRequest.name(), channel);
                        }
                        case "webhook" -> {
                            // Webhook delivery reuses the existing gRPC notification mechanism
                            // handled by TriggerNotificationService in the caller
                            routedMethods.add("webhook");
                            log.debug("Routed trigger '{}' to webhook for agent '{}'",
                                    triggerRequest.name(), settings.getApiKeyPubId());
                        }
                        default -> log.warn("Unknown triggersTo value '{}' for agent '{}'",
                                settings.getTriggersTo(), settings.getApiKeyPubId());
                    }
                } catch (Exception e) {
                    log.warn("Failed to route trigger '{}' to agent '{}': {}",
                            triggerRequest.name(), settings.getApiKeyPubId(), e.getMessage());
                }
            }

            if (!routedMethods.isEmpty()) {
                triggerLog.setRoutedTo(String.join(",", routedMethods));
                triggerLogRepository.save(triggerLog);
            }
        } catch (Exception e) {
            log.warn("Failed to route trigger '{}' to agents: {}", triggerRequest.name(), e.getMessage());
        }
    }
}
