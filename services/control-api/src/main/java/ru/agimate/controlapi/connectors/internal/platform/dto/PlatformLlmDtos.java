package ru.agimate.controlapi.connectors.internal.platform.dto;

import java.util.List;
import java.util.Map;

/**
 * View models of the LLM tools of the platform connector ({@code PlatformLlmToolService}): providers,
 * provider models, the provider catalog, quotas, agent-LLM bindings and usage. Flat and
 * LLM-friendly (public ids as strings), assembled by the connector from the repositories and the
 * service-layer commands. See {@link PlatformDtos} for the shared-file rules.
 *
 * <p>Secrets never travel through tools in either direction: no record here carries an {@code apiKey}
 * — providers expose {@code apiKeyMask} only (a masked form, e.g. {@code sk-AbCd...WxYz}), and
 * creation is a setup link ({@link LlmProviderSetup}), not a tool-created row.
 */
public final class PlatformLlmDtos {

    private PlatformLlmDtos() {
    }

    // ---- providers -------------------------------------------------------------------------

    /** {@code create_llm_provider} answer: the tool writes nothing — a link the user opens to enter the key. */
    public record LlmProviderSetup(String status, String setupUrl) {
    }

    public record LlmProviderList(List<LlmProviderBrief> items, boolean truncated) {
    }

    public record LlmProviderBrief(String id, String name, String providerType, String baseUrl,
                                   String apiKeyMask, boolean enabled, String modelsRefreshedAt) {
    }

    /** The brief plus the full configuration — the model-facing shape of get/create/update. */
    public record LlmProviderDetail(String id, String name, String providerType, String baseUrl,
                                    String apiKeyMask, boolean enabled, String modelsRefreshedAt,
                                    Map<String, List<String>> purposePriority,
                                    Map<String, Object> extraBody, String mediaTransport) {
    }

    // ---- provider models -------------------------------------------------------------------

    public record LlmProviderModelList(List<LlmProviderModel> models, boolean truncated) {
    }

    public record LlmProviderModel(String id, String model, String displayName, Integer contextWindow,
                                   Integer maxOutputTokens, List<String> inputModalities,
                                   List<String> outputModalities, String status,
                                   Map<String, Object> extraBody) {
    }

    // ---- provider catalog ------------------------------------------------------------------

    public record LlmProviderCatalogList(List<LlmProviderCatalogEntry> items) {
    }

    public record LlmProviderCatalogEntry(String code, String name, String description, String providerType,
                                          String baseUrl, String mediaTransport,
                                          Map<String, List<String>> purposePriority,
                                          String apiKeyUrl, boolean enabled) {
    }

    // ---- quotas ----------------------------------------------------------------------------

    public record LlmQuotaList(List<LlmQuotaItem> items, boolean truncated) {
    }

    public record LlmQuotaItem(String id, String subjectKind, String window, long limitTokens) {
    }

    // ---- agent LLM bindings ----------------------------------------------------------------

    public record AgentLlmBindingList(List<AgentLlmBinding> items, boolean truncated) {
    }

    public record AgentLlmBinding(String purpose, String providerId, String providerName, String model) {
    }

    // ---- usage -----------------------------------------------------------------------------

    public record LlmUsageList(List<LlmUsageItem> items, boolean truncated) {
    }

    public record LlmUsageItem(String providerId, String providerName, String source,
                               List<LlmUsageWindow> windows) {
    }

    public record LlmUsageWindow(String window, String windowStart, long usedTokens, long requests,
                                 Long limitTokens, Long remainingTokens) {
    }
}
