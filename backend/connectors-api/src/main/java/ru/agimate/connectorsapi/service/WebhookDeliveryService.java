package ru.agimate.connectorsapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectorsapi.database.entities.WebhookDelivery;
import ru.agimate.connectorsapi.database.entities.WebhookUrl;
import ru.agimate.connectorsapi.database.repositories.WebhookDeliveryRepository;
import ru.agimate.connectorsapi.database.repositories.WebhookUrlRepository;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookDeliveryService {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_RESPONSE_BODY_LENGTH = 10000;

    private final WebhookUrlRepository webhookUrlRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    @Transactional()
    public void handleEvent(
            String eventName,
            UUID userPubId,
            UUID credentialId,
            String deviceId,
            Object params
    ) {
        log.debug("Handling event: {} for user: {}, device: {}, credential: {}",
                eventName, userPubId, deviceId, credentialId);

        try {
            String normalizedEventName = eventName.toLowerCase();

            // Find active webhooks subscribed to this event type
            List<WebhookUrl> webhooks = webhookUrlRepository
                    .findActiveByUserPubIdAndEventType(userPubId, normalizedEventName);

            if (webhooks.isEmpty()) {
                log.debug("No active webhooks found for event: {} and user: {}", normalizedEventName, userPubId);
                return;
            }

            log.info("Found {} active webhook(s) for event: {}", webhooks.size(), normalizedEventName);

            Map<String, Object> eventPayload = buildEventPayload(
                    normalizedEventName, userPubId, credentialId, deviceId, params);

            for (WebhookUrl webhook : webhooks) {
                deliverWebhook(webhook, normalizedEventName, eventPayload);
            }

        } catch (Exception e) {
            log.error("Error handling event {}: {}", eventName, e.getMessage(), e);
        }
    }

    private Map<String, Object> buildEventPayload(
            String eventName,
            UUID userPubId,
            UUID credentialId,
            String deviceId,
            Object params
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", eventName);
        payload.put("userId", userPubId.toString());
        payload.put("timestamp", LocalDateTime.now().toString());

        if (credentialId != null) {
            payload.put("credentialId", credentialId.toString());
        }

        if (deviceId != null) {
            payload.put("deviceId", deviceId);
        }

        if (params instanceof Map) {
            payload.put("data", params);
        } else if (params instanceof JsonNode jsonNode) {
            payload.put("data", JsonUtils.objectToMap(jsonNode));
        } else if (params != null) {
            payload.put("data", params);
        } else {
            payload.put("data", Collections.emptyMap());
        }

        return payload;
    }

    private void deliverWebhook(WebhookUrl webhook, String eventType, Map<String, Object> eventPayload) {
        long startTime = System.currentTimeMillis();
        WebhookDelivery.WebhookDeliveryBuilder deliveryBuilder = WebhookDelivery.builder()
                .webhookUrlId(webhook.getId())
                .eventType(eventType)
                .userPubId(webhook.getUserPubId())
                .requestUrl(webhook.getUrl())
                .requestPayload(eventPayload)
                .triggeredAt(LocalDateTime.now());

        if (eventPayload.get("credentialId") != null) {
            deliveryBuilder.credentialId(UUID.fromString((String) eventPayload.get("credentialId")));
        }
        if (eventPayload.get("deviceId") != null) {
            deliveryBuilder.deviceId((String) eventPayload.get("deviceId"));
        }

        try {
            String jsonPayload = JsonUtils.writeValueAsString(eventPayload);
            RequestBody body = RequestBody.create(jsonPayload, JSON_MEDIA_TYPE);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(webhook.getUrl())
                    .post(body)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Agimate-Webhooks/1.0");

            if (webhook.hasAuth()) {
                requestBuilder.header("Authorization", webhook.getAuthHeader());
            }

            Request request = requestBuilder.build();

            log.debug("Calling webhook: {} for event: {}", webhook.getUrl(), eventType);

            try (Response response = httpClient.newCall(request).execute()) {
                long duration = System.currentTimeMillis() - startTime;
                String responseBody = response.body() != null ? response.body().string() : "";

                if (responseBody.length() > MAX_RESPONSE_BODY_LENGTH) {
                    responseBody = responseBody.substring(0, MAX_RESPONSE_BODY_LENGTH) + "... (truncated)";
                }

                deliveryBuilder
                        .responseStatusCode(response.code())
                        .responseBody(responseBody)
                        .durationMs(duration);

                if (response.isSuccessful()) {
                    log.info("Webhook delivered successfully: {} ({}ms, status: {})",
                            webhook.getUrl(), duration, response.code());
                } else {
                    log.warn("Webhook delivery failed: {} ({}ms, status: {})",
                            webhook.getUrl(), duration, response.code());
                }
            }

        } catch (IOException e) {
            long duration = System.currentTimeMillis() - startTime;
            deliveryBuilder
                    .errorMessage(e.getMessage())
                    .durationMs(duration);

            log.error("Failed to deliver webhook to {}: {}", webhook.getUrl(), e.getMessage());

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            deliveryBuilder
                    .errorMessage(e.getMessage())
                    .durationMs(duration);

            log.error("Unexpected error delivering webhook to {}: {}", webhook.getUrl(), e.getMessage(), e);
        }

        saveDeliveryRecord(deliveryBuilder.build(), webhook.getId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveDeliveryRecord(WebhookDelivery delivery, Long webhookUrlId) {
        try {
            webhookDeliveryRepository.save(delivery);

            webhookUrlRepository.findById(webhookUrlId)
                    .ifPresent(webhook -> {
                        webhook.setLastTriggeredAt(LocalDateTime.now());
                        webhookUrlRepository.save(webhook);
                    });

        } catch (Exception e) {
            log.error("Failed to save webhook delivery record: {}", e.getMessage(), e);
        }
    }
}
