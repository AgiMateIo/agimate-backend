package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateLlmQuotaRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmQuotaResponse;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.llm.LlmQuotaService;

import java.util.List;
import java.util.UUID;

/**
 * Квоты провайдера: пользователь ограничивает расход своего (TOTAL — потолок кошелька,
 * AGENT — лимит каждому агенту), ADMIN дополнительно управляет квотами платформенного
 * (free-tier: USER — «каждому пользователю N за окно»). Владение/роль проверяются на каждом вызове.
 */
@RestController
@RequestMapping(ManageLlmProviderQuotaController.PATH)
@RequiredArgsConstructor
@Tag(name = "LLM Provider Quotas", description = "Token quotas on the user's own LLM providers")
public class ManageLlmProviderQuotaController {

    public static final String PATH = ManageLlmProviderController.PATH + "/{providerId}/quotas";

    private final LlmProviderService llmProviderService;
    private final LlmQuotaService llmQuotaService;

    @Operation(summary = "List quotas of the provider")
    @GetMapping("/")
    public SuccessResponse<List<LlmQuotaResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID providerId
    ) {
        UUID userId = UUID.fromString(principal.id());
        llmProviderService.requireOwnedOrPlatformAdmin(providerId, userId, principal.isAdmin());
        return SuccessResponse.ok(llmQuotaService.listForProvider(providerId).stream()
                .map(LlmQuotaResponse::from)
                .toList());
    }

    @Operation(summary = "Create a quota", description = "409 when a quota for the same subject and window exists")
    @PostMapping("/")
    public SuccessResponse<LlmQuotaResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID providerId,
            @Valid @RequestBody CreateLlmQuotaRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        llmProviderService.requireOwnedOrPlatformAdmin(providerId, userId, principal.isAdmin());
        return SuccessResponse.ok(LlmQuotaResponse.from(
                llmQuotaService.create(providerId, request.subjectKind(), request.window(), request.limitTokens())));
    }

    @Operation(summary = "Delete a quota")
    @DeleteMapping("/{quotaId}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID providerId,
            @PathVariable UUID quotaId
    ) {
        UUID userId = UUID.fromString(principal.id());
        llmProviderService.requireOwnedOrPlatformAdmin(providerId, userId, principal.isAdmin());
        llmQuotaService.delete(providerId, quotaId);
        return SuccessResponse.empty();
    }
}
