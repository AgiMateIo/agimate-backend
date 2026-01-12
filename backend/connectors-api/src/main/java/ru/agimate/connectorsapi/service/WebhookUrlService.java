package ru.agimate.connectorsapi.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateWebhookRegistrationRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateWebhookRegistrationRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.WebhookRegistrationResponse;
import ru.agimate.connectorsapi.database.entities.WebhookUrl;
import ru.agimate.connectorsapi.database.entities.WebhookUrlEvent;
import ru.agimate.connectorsapi.database.repositories.WebhookUrlEventRepository;
import ru.agimate.connectorsapi.database.repositories.WebhookUrlRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class WebhookUrlService {

    private final WebhookUrlRepository webhookUrlRepository;
    private final WebhookUrlEventRepository webhookUrlEventRepository;
    private final Environment environment;
    private final EntityManager entityManager;

    public List<WebhookRegistrationResponse> getAllByUser(UUID userPubId) {
        return webhookUrlRepository.findByUserPubIdNotDeleted(userPubId)
                .stream()
                .map(WebhookRegistrationResponse::from)
                .toList();
    }

    public WebhookRegistrationResponse getById(UUID pubId, UUID userPubId) {
        WebhookUrl webhook = findByPubIdAndUser(pubId, userPubId);
        return WebhookRegistrationResponse.from(webhook);
    }

    @Transactional
    public WebhookRegistrationResponse create(CreateWebhookRegistrationRequest request, UUID userPubId) {
        // Validate URL scheme (HTTPS only in non-local profiles)
        validateUrlScheme(request.url());

        // Check if URL already exists for this user
        webhookUrlRepository.findByUserPubIdAndUrl(userPubId, request.url())
                .ifPresent(existing -> {
                    throw new BadRequestStatusException(
                            "Webhook URL already exists for this user"
                    );
                });

        // Create webhook URL without events first
        WebhookUrl webhook = WebhookUrl.builder()
                .userPubId(userPubId)
                .name(request.name())
                .description(request.description())
                .url(request.url())
                .authHeader(request.authHeader())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .events(new ArrayList<>())
                .build();

        // Save to get the ID
        WebhookUrl saved = webhookUrlRepository.save(webhook);

        // Now add event types with the webhook ID
        for (String eventType : request.eventTypes()) {
            String normalizedEventType = eventType.toLowerCase();
            WebhookUrlEvent event = WebhookUrlEvent.builder()
                    .webhookUrlId(saved.getId())
                    .eventType(normalizedEventType)
                    .userPubId(userPubId)
                    .build();
            saved.getEvents().add(event);
        }

        // Save again to persist events
        saved = webhookUrlRepository.save(saved);
        log.info("Created webhook URL {} with {} event types (user: {})",
                saved.getPubId(), request.eventTypes().size(), userPubId);

        return WebhookRegistrationResponse.from(saved);
    }

    @Transactional
    public WebhookRegistrationResponse update(UUID pubId, UpdateWebhookRegistrationRequest request, UUID userPubId) {
        WebhookUrl webhook = findByPubIdAndUser(pubId, userPubId);

        // Update fields if provided
        if (request.name() != null) {
            webhook.setName(request.name());
        }
        if (request.description() != null) {
            webhook.setDescription(request.description());
        }
        if (request.url() != null) {
            validateUrlScheme(request.url());

            // Check if new URL conflicts with existing webhook
            if (!webhook.getUrl().equals(request.url())) {
                webhookUrlRepository.findByUserPubIdAndUrl(userPubId, request.url())
                        .ifPresent(existing -> {
                            if (!existing.getId().equals(webhook.getId())) {
                                throw new BadRequestStatusException(
                                        "Webhook URL already exists for this user"
                                );
                            }
                        });
            }
            webhook.setUrl(request.url());
        }
        if (request.authHeader() != null) {
            webhook.setAuthHeader(request.authHeader());
        }
        if (request.enabled() != null) {
            webhook.setEnabled(request.enabled());
        }

        // Update event types if provided (full replacement with upsert logic)
        if (request.eventTypes() != null && !request.eventTypes().isEmpty()) {
            // Normalize and deduplicate requested event types
            Set<String> requestedEventTypes = new HashSet<>();
            for (String eventType : request.eventTypes()) {
                requestedEventTypes.add(eventType.toLowerCase());
            }

            // Get current event types
            Set<String> currentEventTypes = new HashSet<>();
            for (WebhookUrlEvent event : webhook.getEvents()) {
                currentEventTypes.add(event.getEventType());
            }

            log.debug("Updating event types for webhook {}: current={}, requested={}",
                    pubId, currentEventTypes, requestedEventTypes);

            // Remove events that are no longer needed
            webhook.getEvents().removeIf(event -> !requestedEventTypes.contains(event.getEventType()));

            // Add new events (only those that don't exist yet)
            for (String eventType : requestedEventTypes) {
                if (!currentEventTypes.contains(eventType)) {
                    WebhookUrlEvent event = WebhookUrlEvent.builder()
                            .webhookUrlId(webhook.getId())
                            .eventType(eventType)
                            .userPubId(userPubId)
                            .build();
                    webhook.getEvents().add(event);
                }
            }

            log.info("Updated event types for webhook {}: {} event types (added: {}, removed: {})",
                    pubId, requestedEventTypes.size(),
                    requestedEventTypes.size() - currentEventTypes.size() + (currentEventTypes.size() - webhook.getEvents().size()),
                    currentEventTypes.size() - webhook.getEvents().size());
        }

        WebhookUrl saved = webhookUrlRepository.save(webhook);
        log.info("Updated webhook URL {} (user: {})", pubId, userPubId);

        return WebhookRegistrationResponse.from(saved);
    }

    @Transactional
    public void delete(UUID pubId, UUID userPubId) {
        WebhookUrl webhook = findByPubIdAndUser(pubId, userPubId);
        webhookUrlRepository.softDelete(webhook.getId(), LocalDateTime.now());
        log.info("Soft deleted webhook URL {}", pubId);
    }

    private WebhookUrl findByPubIdAndUser(UUID pubId, UUID userPubId) {
        return webhookUrlRepository.findByPubIdAndUserPubIdNotDeleted(pubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("Webhook not found"));
    }

    private void validateUrlScheme(String url) {
        // Allow HTTP only in local profile
        boolean isLocalProfile = List.of(environment.getActiveProfiles()).contains("local");

        if (!isLocalProfile && url.startsWith("http://")) {
            throw new BadRequestStatusException("HTTPS is required for webhook URLs in production");
        }
    }
}
