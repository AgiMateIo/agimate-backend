package ru.agimate.deviceapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.service.CentrifugoService;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerEmitterService {

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
    public void fireTriggerToTeam(UUID userPubId, Long agenticTeamId, Trigger trigger) {
        List<Agent> agents = agentRepository.findByUserPubIdAndAgenticTeamId(userPubId, agenticTeamId);

        for (Agent agent : agents) {
            fireTrigger(agent, trigger);
        }
    }


    public void fireTrigger(Agent agent, Trigger trigger) {
        try {
            switch (agent.getTriggerDestination()) {
                case CENTRIFUGO -> sendToCentrifugo(agent, trigger);
                case WEBHOOK -> sendToWebhook(agent, trigger);
            }
        } catch (Exception e) {
            log.warn("Failed to send trigger '{}' to agent '{}' via {}: {}",
                    trigger.name(), agent.getPubId(), agent.getTriggerDestination(), e.getMessage());
        }
    }

    private void sendToCentrifugo(Agent agent, Trigger trigger) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "trigger");
        payload.put("payload", trigger);

        centrifugoService.publishMessage("agent:" + agent.getPubId(), payload);
        log.debug("Trigger '{}' sent to agent '{}' via centrifugo", trigger.name(), agent.getPubId());
    }

    private void sendToWebhook(Agent agent, Trigger trigger) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "trigger");
        payload.put("payload", trigger);

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
                    trigger.name(), agent.getPubId(), response.code());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
