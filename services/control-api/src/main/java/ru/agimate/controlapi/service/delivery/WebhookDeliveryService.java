package ru.agimate.controlapi.service.delivery;

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
import ru.agimate.controlapi.controller.manage.dto.WebhookDeliveryLogResponse;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.entities.TriggerLogAgent;
import ru.agimate.controlapi.database.entities.WebhookDeliveryLog;
import ru.agimate.controlapi.database.repositories.WebhookDeliveryLogRepository;
import ru.agimate.controlapi.service.channel.handler.dto.InboundMessage;
import ru.agimate.controlapi.service.dto.AgentMessage;
import ru.agimate.controlapi.service.trigger.Channels;
import ru.agimate.controlapi.service.trigger.Trigger;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookDeliveryService implements AgentDeliveryHandler {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BODY_LENGTH = 10000;

    private final WebhookDeliveryLogRepository webhookDeliveryLogRepository;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Override
    public AgentType getAgentType() {
        return AgentType.WEBHOOK;
    }

    @Override
    @Async
    public void deliverTrigger(TriggerLogAgent triggerLogAgent, Trigger trigger, Channels channels, InboundMessage inbound) {
        Agent agent = triggerLogAgent.getAgent();
        String type = channels != null ? "channel_message" : "trigger";
        AgentMessage<Trigger> message = new AgentMessage<>(
                agent.getId().toString(),
                triggerLogAgent.getId().toString(),
                type,
                channels,
                inbound,
                trigger);
        WebhookDeliveryLog.WebhookDeliveryLogBuilder logBuilder = WebhookDeliveryLog.builder()
                .triggerLogAgent(triggerLogAgent)
                .requestUrl(agent.getWebhookUrl())
                .requestPayload(JsonUtils.objectToMap(message))
                .deliveredAt(LocalDateTime.now());

        sendWebhook(agent, message, logBuilder);
        saveDeliveryLog(logBuilder.build());
    }

    private void sendWebhook(Agent agent, AgentMessage<?> message, WebhookDeliveryLog.WebhookDeliveryLogBuilder logBuilder) {
        long startTime = System.currentTimeMillis();
        try {
            String jsonPayload = JsonUtils.writeValueAsString(message);
            RequestBody body = RequestBody.create(jsonPayload, JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(agent.getWebhookUrl())
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Agimate-Webhooks/1.0");

            if (agent.hasWebhookAuth()) {
                requestBuilder.header("Authorization", agent.getWebhookAuthHeader());
            }

            log.debug("Delivering webhook to {} (type={})", agent.getWebhookUrl(), message.type());

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
                logBuilder.error(e.getMessage()).durationMs(duration);
            }
            log.error("Failed to deliver webhook to {}: {}", agent.getWebhookUrl(), e.getMessage());
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            if (logBuilder != null) {
                logBuilder.error(e.getMessage()).durationMs(duration);
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
    public Page<WebhookDeliveryLogResponse> getDeliveryLogs(UUID userId, UUID agentId, int page, int size) {
        Page<WebhookDeliveryLog> logs;
        if (agentId != null) {
            logs = webhookDeliveryLogRepository.findByAgentId(agentId, PageRequest.of(page, size));
        } else {
            logs = webhookDeliveryLogRepository.findByUserId(userId, PageRequest.of(page, size));
        }
        return logs.map(WebhookDeliveryLogResponse::from);
    }

}
