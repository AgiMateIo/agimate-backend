package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateWebhookRegistrationRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateWebhookRegistrationRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.WebhookDeliveryResponse;
import ru.agimate.connectorsapi.controller.manage.dto.response.WebhookRegistrationResponse;
import ru.agimate.connectorsapi.database.repositories.WebhookLogRepository;
import ru.agimate.connectorsapi.database.repositories.WebhookRepository;
import ru.agimate.connectorsapi.service.WebhookService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(WebhookRegistrationManageController.PATH)
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Manage webhook registrations for event notifications")
public class WebhookRegistrationManageController {

    public static final String PATH = "/manage/webhooks";

    private final WebhookService webhookService;
    private final WebhookRepository webhookRepository;
    private final WebhookLogRepository webhookLogRepository;

    @Operation(summary = "Get all webhook registrations",
            description = "Retrieve all webhook registrations for the current user")
    @GetMapping("/")
    public SuccessResponse<List<WebhookRegistrationResponse>> getAllWebhooks() {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookService.getAllByUser(userPubId));
    }

    @Operation(summary = "Get webhook registration details",
            description = "Retrieve a specific webhook registration by ID")
    @GetMapping("/{webhookId}")
    public SuccessResponse<WebhookRegistrationResponse> getWebhook(
            @PathVariable UUID webhookId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookService.getById(webhookId, userPubId));
    }

    @Operation(summary = "Get webhook delivery history",
            description = "Retrieve delivery history for a specific webhook")
    @GetMapping("/{webhookId}/deliveries")
    public SuccessResponse<Page<WebhookDeliveryResponse>> getWebhookDeliveries(
            @PathVariable UUID webhookId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();

        // Verify webhook belongs to user
        var webhook = webhookRepository.findByPubIdAndUserPubIdNotDeleted(webhookId, userPubId)
                .orElseThrow(() -> new ru.agimate.common.rest.error.NotFoundStatusException("Webhook not found"));

        // Get deliveries
        Page<WebhookDeliveryResponse> deliveries = webhookLogRepository
                .findByWebhookId(webhook.getId(), PageRequest.of(page, size))
                .map(WebhookDeliveryResponse::from);

        return SuccessResponse.ok(deliveries);
    }

    @Operation(summary = "Create new webhook registration",
            description = "Register a new webhook to receive event notifications")
    @PostMapping
    public SuccessResponse<WebhookRegistrationResponse> createWebhook(
            @Valid @RequestBody CreateWebhookRegistrationRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookService.create(request, userPubId));
    }

    @Operation(summary = "Update webhook registration",
            description = "Update an existing webhook registration (partial update supported)")
    @PutMapping("/{webhookId}")
    public SuccessResponse<WebhookRegistrationResponse> updateWebhook(
            @PathVariable UUID webhookId,
            @Valid @RequestBody UpdateWebhookRegistrationRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookService.update(webhookId, request, userPubId));
    }

    @Operation(summary = "Delete webhook registration",
            description = "Soft delete a webhook registration")
    @DeleteMapping("/{webhookId}")
    public SuccessResponse<Void> deleteWebhook(
            @PathVariable UUID webhookId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        webhookService.delete(webhookId, userPubId);
        return SuccessResponse.empty();
    }
}
