package ru.agimate.connectorsapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.SecurityUtils;
import ru.agimate.connectorsapi.controller.manage.dto.request.CreateWebhookRegistrationRequest;
import ru.agimate.connectorsapi.controller.manage.dto.request.UpdateWebhookRegistrationRequest;
import ru.agimate.connectorsapi.controller.manage.dto.response.WebhookRegistrationResponse;
import ru.agimate.connectorsapi.service.WebhookRegistrationService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(WebhookRegistrationManageController.PATH)
@RequiredArgsConstructor
@Tag(name = "Webhooks", description = "Manage webhook registrations for event notifications")
public class WebhookRegistrationManageController {

    public static final String PATH = "/manage/webhooks";

    private final WebhookRegistrationService webhookRegistrationService;

    @Operation(summary = "Get all webhook registrations",
            description = "Retrieve all webhook registrations for the current user, optionally filtered by event type")
    @GetMapping("/")
    public SuccessResponse<List<WebhookRegistrationResponse>> getAllWebhooks(
            @RequestParam(required = false) String eventType
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();

        if (eventType != null && !eventType.isBlank()) {
            return SuccessResponse.ok(webhookRegistrationService.getByEventType(userPubId, eventType));
        }

        return SuccessResponse.ok(webhookRegistrationService.getAllByUser(userPubId));
    }

    @Operation(summary = "Get webhook registration details",
            description = "Retrieve a specific webhook registration by ID")
    @GetMapping("/{webhookId}")
    public SuccessResponse<WebhookRegistrationResponse> getWebhook(
            @PathVariable UUID webhookId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookRegistrationService.getById(webhookId, userPubId));
    }

    @Operation(summary = "Create new webhook registration",
            description = "Register a new webhook to receive event notifications")
    @PostMapping
    public SuccessResponse<WebhookRegistrationResponse> createWebhook(
            @Valid @RequestBody CreateWebhookRegistrationRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookRegistrationService.create(request, userPubId));
    }

    @Operation(summary = "Update webhook registration",
            description = "Update an existing webhook registration (partial update supported)")
    @PutMapping("/{webhookId}")
    public SuccessResponse<WebhookRegistrationResponse> updateWebhook(
            @PathVariable UUID webhookId,
            @Valid @RequestBody UpdateWebhookRegistrationRequest request
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        return SuccessResponse.ok(webhookRegistrationService.update(webhookId, request, userPubId));
    }

    @Operation(summary = "Delete webhook registration",
            description = "Soft delete a webhook registration")
    @DeleteMapping("/{webhookId}")
    public SuccessResponse<Void> deleteWebhook(
            @PathVariable UUID webhookId
    ) {
        UUID userPubId = SecurityUtils.getCurrentUserPubId();
        webhookRegistrationService.delete(webhookId, userPubId);
        return SuccessResponse.empty();
    }
}
