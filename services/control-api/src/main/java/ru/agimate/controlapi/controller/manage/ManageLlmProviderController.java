package ru.agimate.controlapi.controller.manage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import ru.agimate.common.rest.SuccessResponse;
import ru.agimate.common.security.jwt.AgimateUserPrincipal;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.CreatePlatformLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderCatalogResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderModelResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.LlmProviderResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.RefreshModelsResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateLlmProviderRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpsertModelExtraBodyRequest;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.llm.catalog.LlmProviderCatalogService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ManageLlmProviderController.PATH)
@RequiredArgsConstructor
@Tag(name = "LLM Providers", description = "Manage LLM provider credentials (OpenAI, Anthropic, Gemini, OpenAI-compatible)")
public class ManageLlmProviderController {

    public static final String PATH = "/manage/llm-providers";

    private final LlmProviderService llmProviderService;
    private final LlmProviderCatalogService llmProviderCatalogService;

    @Operation(summary = "List LLM providers for the current user")
    @GetMapping("/")
    public SuccessResponse<List<LlmProviderResponse>> list(
            @AuthenticationPrincipal AgimateUserPrincipal principal
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.listForUser(userId, principal.isAdmin()));
    }

    @Operation(summary = "Catalogue of known providers to prefill the create form with "
            + "(base URL, media dialect, models to start from)")
    @GetMapping("/catalog/")
    public SuccessResponse<List<LlmProviderCatalogResponse>> catalog() {
        return SuccessResponse.ok(llmProviderCatalogService.list());
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

    @Operation(summary = "Create the platform (free-tier) LLM provider. ADMIN only; "
            + "name is forced to \"platform\", created disabled")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/platform")
    public SuccessResponse<LlmProviderResponse> createPlatform(
            @Valid @RequestBody CreatePlatformLlmProviderRequest request
    ) {
        return SuccessResponse.ok(llmProviderService.createPlatformProvider(request));
    }

    @Operation(summary = "Get an LLM provider by id")
    @GetMapping("/{id}")
    public SuccessResponse<LlmProviderResponse> get(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.getForUser(id, userId, principal.isAdmin()));
    }

    @Operation(summary = "Update an LLM provider (partial)")
    @PatchMapping("/{id}")
    public SuccessResponse<LlmProviderResponse> update(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateLlmProviderRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.update(id, userId, principal.isAdmin(), request));
    }

    @Operation(summary = "Delete an LLM provider (cascades to agent bindings)")
    @DeleteMapping("/{id}")
    public SuccessResponse<Void> delete(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        llmProviderService.delete(id, userId, principal.isAdmin());
        return SuccessResponse.empty();
    }

    @Operation(summary = "Refresh available models from the provider")
    @PostMapping("/{id}/refresh-models")
    public SuccessResponse<RefreshModelsResponse> refreshModels(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.refreshModels(id, userId, principal.isAdmin()));
    }

    @Operation(summary = "List the provider's model registry (metadata, availability status, "
            + "per-model extra_body)")
    @GetMapping("/{id}/models/")
    public SuccessResponse<List<LlmProviderModelResponse>> listModels(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(llmProviderService.listModelsForUser(id, userId, principal.isAdmin()));
    }

    @Operation(summary = "Set or clear per-model extra_body (upserts the registry row; model id "
            + "goes in the body — it may contain slashes)")
    @PutMapping("/{id}/models/extra-body")
    public SuccessResponse<LlmProviderModelResponse> upsertModelExtraBody(
            @AuthenticationPrincipal AgimateUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpsertModelExtraBodyRequest request
    ) {
        UUID userId = UUID.fromString(principal.id());
        return SuccessResponse.ok(
                llmProviderService.upsertModelExtraBody(id, userId, principal.isAdmin(), request));
    }
}
