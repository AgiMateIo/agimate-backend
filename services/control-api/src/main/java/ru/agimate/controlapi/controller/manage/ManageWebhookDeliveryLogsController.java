package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.WebhookDeliveryLogResponse;
import ru.agimate.controlapi.service.delivery.WebhookTransport;

import java.util.UUID;

@RestController
@RequestMapping(ManageWebhookDeliveryLogsController.PATH)
@RequiredArgsConstructor
@Tag(name = "Webhook Delivery Logs", description = "View webhook delivery history")
public class ManageWebhookDeliveryLogsController {

    public static final String PATH = "/manage/webhook-deliveries";

    private final WebhookTransport webhookTransport;

    @Operation(
            summary = "List webhook delivery logs",
            description = "Returns the current user's webhook delivery logs, optionally filtered by agent id"
    )
    @GetMapping("/")
    public SuccessResponse<Page<WebhookDeliveryLogResponse>> getDeliveryLogs(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @RequestParam(required = false) UUID agentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(webhookTransport.getDeliveryLogs(userId, agentId, page, size));
    }
}
