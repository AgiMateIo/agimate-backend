package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.repositories.AgentRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class BoardTriggerService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    private final AgentRepository agentRepository;
    private final CentrifugoService centrifugoService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Async
    public void fireTrigger(UUID userPubId, Long agenticTeamId, String triggerName, Map<String, Object> triggerData) {
        List<Agent> agents = agentRepository.findByUserPubIdAndAgenticTeamId(userPubId, agenticTeamId);

        for (Agent agent : agents) {
            switch (agent.getTriggersTo()) {
                case "centrifugo" -> sendToCentrifugo(agent, triggerName, triggerData);
                case "webhook" -> sendToWebhook(agent, userPubId, triggerName, triggerData);
                default -> { /* ignore */ }
            }
        }
    }

    private void sendToCentrifugo(Agent agent, String triggerName, Map<String, Object> triggerData) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "trigger");
            payload.put("triggerName", triggerName);
            payload.put("triggerData", triggerData);
            payload.put("occurredAt", Instant.now().toString());

            String channel = "agent:" + agent.getApiKeyPubId();
            centrifugoService.publishMessage(channel, payload);
            log.debug("Board trigger '{}' sent to agent '{}' via centrifugo", triggerName, agent.getApiKeyPubId());
        } catch (Exception e) {
            log.warn("Failed to send board trigger '{}' to agent '{}' via centrifugo: {}",
                    triggerName, agent.getApiKeyPubId(), e.getMessage());
        }
    }

    private void sendToWebhook(Agent agent, UUID userPubId, String triggerName, Map<String, Object> triggerData) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", triggerName);
            payload.put("userId", userPubId.toString());
            payload.put("timestamp", LocalDateTime.now().toString());
            payload.put("data", triggerData);

            String jsonPayload = JsonUtils.writeValueAsString(payload);
            RequestBody body = RequestBody.create(jsonPayload, JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(agent.getWebhookUrl())
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Agimate-Webhooks/1.0");

            if (agent.hasWebhookAuth()) {
                requestBuilder.header("Authorization", agent.getWebhookAuthHeader());
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                log.debug("Board trigger '{}' sent to agent '{}' via webhook, status={}",
                        triggerName, agent.getApiKeyPubId(), response.code());
            }
        } catch (Exception e) {
            log.warn("Failed to send board trigger '{}' to agent '{}' via webhook: {}",
                    triggerName, agent.getApiKeyPubId(), e.getMessage());
        }
    }
}
