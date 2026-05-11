package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.controller.manage.dto.WebhookDeliveryLogResponse;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;
import ru.agimate.deviceapi.database.entities.WebhookDeliveryLog;
import ru.agimate.deviceapi.database.repositories.WebhookDeliveryLogRepository;
import ru.agimate.deviceapi.service.dto.IToolResult;
import ru.agimate.deviceapi.service.trigger.ChannelContext;
import ru.agimate.deviceapi.service.trigger.TriggerMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BODY_LENGTH = 10000;

    private final WebhookDeliveryLogRepository webhookDeliveryLogRepository;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Async
    public void deliverWebhook(Agent agent, TriggerLogAgent triggerLogAgent) {
        deliverWebhook(agent, triggerLogAgent, null);
    }

    @Async
    public void deliverWebhook(Agent agent, TriggerLogAgent triggerLogAgent, ChannelContext channelContext) {
        Map<String, Object> payload = buildEnvelope("trigger", TriggerMapper.map(triggerLogAgent.getTriggerLog(), channelContext));
        WebhookDeliveryLog.WebhookDeliveryLogBuilder logBuilder = WebhookDeliveryLog.builder()
                .triggerLogAgent(triggerLogAgent)
                .requestUrl(agent.getWebhookUrl())
                .requestPayload(payload)
                .deliveredAt(LocalDateTime.now());

        sendWebhook(agent, payload, logBuilder);
        saveDeliveryLog(logBuilder.build());
    }

    @Async
    public void deliverToolResult(Agent agent, IToolResult toolResult) {
        Map<String, Object> payload = buildEnvelope("toolResult", toolResult);
        sendWebhook(agent, payload, null);
    }

    private void sendWebhook(Agent agent, Map<String, Object> payload, WebhookDeliveryLog.WebhookDeliveryLogBuilder logBuilder) {
        long startTime = System.currentTimeMillis();
        try {
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

            log.debug("Delivering webhook to {} (type={})", agent.getWebhookUrl(), payload.get("type"));

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "";

                if (responseBody.length() > MAX_RESPONSE_BODY_LENGTH) {
                    responseBody = responseBody.substring(0, MAX_RESPONSE_BODY_LENGTH) + "... (truncated)";
                }

                if (logBuilder != null) {
                    logBuilder
                            .responseStatusCode(response.code())
                            .responseBody(responseBody)
                            .durationMs(duration);
                }

                if (response.isSuccessful()) {
                    log.info("Webhook delivered successfully: {} ({}ms, status: {})",
                            agent.getWebhookUrl(), duration, response.code());
                } else {
                    log.warn("Webhook delivery failed: {} ({}ms, status: {})",
                            agent.getWebhookUrl(), duration, response.code());
                }
            }
        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            if (logBuilder != null) {
                logBuilder.errorMessage(e.getMessage()).durationMs(duration);
            }
            log.error("Failed to deliver webhook to {}: {}", agent.getWebhookUrl(), e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            if (logBuilder != null) {
                logBuilder.errorMessage(e.getMessage()).durationMs(duration);
            }
            log.error("Unexpected error delivering webhook to {}: {}", agent.getWebhookUrl(), e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDeliveryLog(WebhookDeliveryLog deliveryLog) {
        try {
            webhookDeliveryLogRepository.save(deliveryLog);
        } catch (Exception e) {
            log.error("Failed to save webhook delivery log: {}", e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public Page<WebhookDeliveryLogResponse> getDeliveryLogs(UUID userPubId, UUID agentPubId, int page, int size) {
        Page<WebhookDeliveryLog> logs;
        if (agentPubId != null) {
            logs = webhookDeliveryLogRepository.findByAgentPubId(agentPubId, PageRequest.of(page, size));
        } else {
            logs = webhookDeliveryLogRepository.findByUserPubId(userPubId, PageRequest.of(page, size));
        }
        return logs.map(WebhookDeliveryLogResponse::from);
    }

    private Map<String, Object> buildEnvelope(String type, Object payloadObj) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("type", type);
        envelope.put("payload", payloadObj);
        return envelope;
    }


}
