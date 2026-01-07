package ru.agimate.connectorsapi.service;

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
import ru.agimate.connectorsapi.database.entities.WebhookRegistration;
import ru.agimate.connectorsapi.database.repositories.WebhookRegistrationRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class WebhookRegistrationService {

    private final WebhookRegistrationRepository webhookRegistrationRepository;
    private final Environment environment;

    public List<WebhookRegistrationResponse> getAllByUser(UUID userPubId) {
        return webhookRegistrationRepository.findByUserPubIdNotDeleted(userPubId)
                .stream()
                .map(WebhookRegistrationResponse::from)
                .toList();
    }

    public List<WebhookRegistrationResponse> getByEventType(UUID userPubId, String eventType) {
        return webhookRegistrationRepository.findByUserPubIdAndEventTypeNotDeleted(userPubId, eventType)
                .stream()
                .map(WebhookRegistrationResponse::from)
                .toList();
    }

    public WebhookRegistrationResponse getById(UUID pubId, UUID userPubId) {
        WebhookRegistration webhook = findByPubIdAndUser(pubId, userPubId);
        return WebhookRegistrationResponse.from(webhook);
    }

    @Transactional
    public WebhookRegistrationResponse create(CreateWebhookRegistrationRequest request, UUID userPubId) {
        // Validate URL scheme (HTTPS only in non-local profiles)
        validateUrlScheme(request.url());

        // Check for duplicates
        webhookRegistrationRepository.findDuplicate(userPubId, request.eventType().toLowerCase(), request.url())
                .ifPresent(existing -> {
                    throw new BadRequestStatusException(
                            "Webhook registration already exists for this event type and URL"
                    );
                });

        WebhookRegistration webhook = WebhookRegistration.builder()
                .userPubId(userPubId)
                .name(request.name())
                .description(request.description())
                .eventType(request.eventType().toLowerCase())
                .url(request.url())
                .authHeader(request.authHeader())
                .enabled(request.enabled() != null ? request.enabled() : true)
                .build();

        WebhookRegistration saved = webhookRegistrationRepository.save(webhook);
        log.info("Created webhook registration {} for event type {} (user: {})",
                saved.getPubId(), request.eventType(), userPubId);

        return WebhookRegistrationResponse.from(saved);
    }

    @Transactional
    public WebhookRegistrationResponse update(UUID pubId, UpdateWebhookRegistrationRequest request, UUID userPubId) {
        WebhookRegistration webhook = findByPubIdAndUser(pubId, userPubId);

        // Update fields if provided
        if (request.name() != null) {
            webhook.setName(request.name());
        }
        if (request.description() != null) {
            webhook.setDescription(request.description());
        }
        if (request.eventType() != null) {
            // Check for duplicate if event type or URL changed
            String newEventType = request.eventType().toLowerCase();
            String newUrl = request.url() != null ? request.url() : webhook.getUrl();

            if (!webhook.getEventType().equals(newEventType) || !webhook.getUrl().equals(newUrl)) {
                webhookRegistrationRepository.findDuplicate(userPubId, newEventType, newUrl)
                        .ifPresent(existing -> {
                            if (!existing.getId().equals(webhook.getId())) {
                                throw new BadRequestStatusException(
                                        "Webhook registration already exists for this event type and URL"
                                );
                            }
                        });
            }
            webhook.setEventType(newEventType);
        }
        if (request.url() != null) {
            validateUrlScheme(request.url());
            webhook.setUrl(request.url());
        }
        if (request.authHeader() != null) {
            webhook.setAuthHeader(request.authHeader());
        }
        if (request.enabled() != null) {
            webhook.setEnabled(request.enabled());
        }

        WebhookRegistration saved = webhookRegistrationRepository.save(webhook);
        log.info("Updated webhook registration {}", pubId);

        return WebhookRegistrationResponse.from(saved);
    }

    @Transactional
    public void delete(UUID pubId, UUID userPubId) {
        WebhookRegistration webhook = findByPubIdAndUser(pubId, userPubId);
        webhookRegistrationRepository.softDelete(webhook.getId(), LocalDateTime.now());
        log.info("Soft deleted webhook registration {}", pubId);
    }

    private WebhookRegistration findByPubIdAndUser(UUID pubId, UUID userPubId) {
        return webhookRegistrationRepository.findByPubIdAndUserPubIdNotDeleted(pubId, userPubId)
                .orElseThrow(() -> new NotFoundStatusException("Webhook registration not found"));
    }

    private void validateUrlScheme(String url) {
        // Allow HTTP only in local profile
        boolean isLocalProfile = List.of(environment.getActiveProfiles()).contains("local");

        if (!isLocalProfile && url.startsWith("http://")) {
            throw new BadRequestStatusException("HTTPS is required for webhook URLs in production");
        }
    }
}
