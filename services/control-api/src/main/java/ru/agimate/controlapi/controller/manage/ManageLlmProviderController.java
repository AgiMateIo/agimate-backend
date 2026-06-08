package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.RefreshModelsResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest;
import ru.agimate.controlapi.service.LlmProviderService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageLlmProviderController.PATH)
@RequiredArgsConstructor
@Tag(name = "LLM Providers", description = "Manage LLM provider credentials (OpenAI, Anthropic, Gemini, OpenAI-compatible)")
public class ManageLlmProviderController {

    public static final String PATH = "/manage/llm-providers";

    private final LlmProviderService llmProviderService;

    @Operation(summary = "List LLM providers for the current user")
    @GetMapping("/")
    public SuccessResponse<List<LlmProviderResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.listForUser(userId));
    }

    @Operation(summary = "Create an LLM provider")
    @PostMapping("/")
    public SuccessResponse<LlmProviderResponse> create(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @Valid @RequestBody CreateLlmProviderRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.create(userId, request));
    }

    @Operation(summary = "Get an LLM provider by id")
    @GetMapping("/{id}")
    public SuccessResponse<LlmProviderResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.getForUser(id, userId));
    }

    @Operation(summary = "Update an LLM provider (partial)")
    @PatchMapping("/{id}")
    public SuccessResponse<LlmProviderResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLlmProviderRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.update(id, userId, request));
    }

    @Operation(summary = "Delete an LLM provider (cascades to agent bindings)")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        llmProviderService.delete(id, userId);
        return SuccessResponse.empty();
    }

    @Operation(summary = "Refresh available models from the provider")
    @PostMapping("/{id}/refresh-models")
    public SuccessResponse<RefreshModelsResponse> refreshModels(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.refreshModels(id, userId));
    }
}
