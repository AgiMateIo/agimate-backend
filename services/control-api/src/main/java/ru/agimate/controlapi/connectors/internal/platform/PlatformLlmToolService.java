package ru.agimate.controlapi.connectors.internal.platform;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BaseHttpStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformDtos.OperationResult;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.AgentLlmBinding;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.AgentLlmBindingList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderBrief;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderCatalogEntry;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderCatalogList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderDetail;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderModel;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmProviderModelList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmQuotaItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmQuotaList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmUsageItem;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmUsageList;
import ru.agimate.controlapi.connectors.internal.platform.dto.PlatformLlmDtos.LlmUsageWindow;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.MediaTransportType;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderCatalogRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.service.AgentLlmService;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.dto.llm.LlmProviderCreateCommand;
import ru.agimate.controlapi.service.dto.llm.LlmProviderUpdateCommand;
import ru.agimate.controlapi.service.dto.llm.LlmUsageSnapshot;
import ru.agimate.controlapi.service.llm.LlmQuotaService;
import ru.agimate.controlapi.service.llm.LlmUsageQueryService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Tools of the platform connector's LLM module — the meta-agent manages LLM providers (BYOK), their
 * quotas, agent↔provider bindings and usage on behalf of its human owner ({@code env.userId}). A thin
 * adapter: reads come from the repositories, writes go through the existing services (command
 * overloads, so as not to drag in {@code controller/**}). Domain {@link BaseHttpStatusException}s are
 * translated into {@link ConnectorException} so the message reaches the agent. Shared guards and
 * parsing live in {@link PlatformToolsSupport}.
 *
 * <p>Secrets are write-only: {@code apiKey} is accepted by create/update and stored encrypted, but no
 * tool ever returns it — providers report {@code apiKeyMask} only (the service's masked form).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformLlmToolService {

    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderModelRepository llmProviderModelRepository;
    private final LlmProviderCatalogRepository llmProviderCatalogRepository;
    private final AgentLlmRepository agentLlmRepository;
    private final AgentRepository agentRepository;
    private final LlmProviderService llmProviderService;
    private final LlmQuotaService llmQuotaService;
    private final AgentLlmService agentLlmService;
    private final LlmUsageQueryService llmUsageQueryService;

    // ---- providers -------------------------------------------------------------------------

    @Tool(name = "list_llm_providers",
            description = "List your LLM providers (BYOK). The platform/free-tier provider is not "
                    + "shown — it is managed by the platform admin. apiKeyMask is a masked form "
                    + "(prefix + first 4 + … + last 4); the key itself is never returned",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public LlmProviderList listLlmProviders() {
        List<LlmProviderBrief> items = llmProviderRepository
                .findAllByUserIdOrderByCreatedAtDesc(PlatformToolsSupport.userId()).stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .map(this::toBrief)
                .toList();
        return new LlmProviderList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "get_llm_provider",
            description = "Get one of your LLM providers with its full configuration: models allowed "
                    + "per purpose, provider-level extra body and the media transport. apiKeyMask is "
                    + "a masked form; the key itself is never returned",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public LlmProviderDetail getLlmProvider(@ToolParam("Provider public ID") String id) {
        LlmProvider provider = PlatformToolsSupport.domain(() -> llmProviderService
                .requireOwned(PlatformToolsSupport.parseUuid(id, "id"), PlatformToolsSupport.userId()));
        return toDetail(provider);
    }

    @Tool(name = "create_llm_provider",
            description = "Create an LLM provider. providerType: OPENAI, ANTHROPIC, GEMINI or "
                    + "OPENAI_COMPATIBLE. apiKey is required and stored encrypted — it appears in the "
                    + "conversation once (what you pass here) and is never returned by any tool. "
                    + "purposePriority maps LlmPurpose (CHAT, IMAGE, VISION, AUDIO_IN, AUDIO_OUT) to an "
                    + "ordered model list; an empty list disables the purpose. Use "
                    + "list_llm_provider_catalog first to get the exact baseUrl form",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public LlmProviderDetail createLlmProvider(
            @ToolParam("Human-readable name, unique per user") String name,
            @ToolParam("Provider type: OPENAI, ANTHROPIC, GEMINI, OPENAI_COMPATIBLE") String providerType,
            @ToolParam(value = "Custom base URL (required for OPENAI_COMPATIBLE)", required = false)
            String baseUrl,
            @ToolParam("API key — stored encrypted, never returned") String apiKey,
            @ToolParam(value = "Models allowed per purpose, in priority order "
                    + "(e.g. {\"CHAT\": [\"m1\", \"m2\"]})", required = false)
            Map<String, List<String>> purposePriority,
            @ToolParam(value = "Provider-level extra body fields", required = false)
            Map<String, Object> extraBody,
            @ToolParam(value = "Media transport: CHAT_MODALITIES (default) or MEDIA_ENDPOINT",
                    required = false) String mediaTransport,
            @ToolParam(value = "Whether the provider starts enabled (default true)", required = false)
            Boolean enabled) {
        LlmProviderCreateCommand command = new LlmProviderCreateCommand(
                PlatformToolsSupport.requireText(name, "name"),
                requireEnum(LlmProviderType.class, providerType, "providerType"),
                PlatformToolsSupport.blankToNull(baseUrl),
                PlatformToolsSupport.requireText(apiKey, "apiKey"),
                toPurposePriority(purposePriority),
                extraBody,
                optionalEnum(MediaTransportType.class, mediaTransport, "mediaTransport"),
                enabled);
        LlmProvider provider = PlatformToolsSupport.domain(
                () -> llmProviderService.create(PlatformToolsSupport.userId(), command));
        return toDetail(provider);
    }

    @Tool(name = "update_llm_provider",
            description = "Partial update of an LLM provider; omitted params are kept. apiKey: pass to "
                    + "replace (never returned), omit to keep. purposePriority/extraBody: an empty map "
                    + "clears, an omitted one keeps the current value",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public LlmProviderDetail updateLlmProvider(
            @ToolParam("Provider public ID") String id,
            @ToolParam(value = "New name (unique per user)", required = false) String name,
            @ToolParam(value = "New base URL; empty string clears", required = false) String baseUrl,
            @ToolParam(value = "New API key — stored encrypted, never returned; omit to keep",
                    required = false) String apiKey,
            @ToolParam(value = "Models allowed per purpose; empty map clears, omitted keeps",
                    required = false) Map<String, List<String>> purposePriority,
            @ToolParam(value = "Provider-level extra body fields; empty map clears, omitted keeps",
                    required = false) Map<String, Object> extraBody,
            @ToolParam(value = "Media transport: CHAT_MODALITIES or MEDIA_ENDPOINT; omitted keeps",
                    required = false) String mediaTransport,
            @ToolParam(value = "Enable or disable the provider", required = false) Boolean enabled) {
        UUID providerId = PlatformToolsSupport.parseUuid(id, "id");
        PlatformToolsSupport.domain(
                () -> llmProviderService.requireOwned(providerId, PlatformToolsSupport.userId()));
        // PATCH semantics: raw values go into the command — null = keep (the service resolves),
        // an empty purposePriority/extraBody map = clear, a non-blank apiKey = replace.
        LlmProviderUpdateCommand command = new LlmProviderUpdateCommand(
                name,
                baseUrl,
                apiKey,
                toPurposePriority(purposePriority),
                extraBody,
                optionalEnum(MediaTransportType.class, mediaTransport, "mediaTransport"),
                enabled);
        LlmProvider provider = PlatformToolsSupport.domain(
                () -> llmProviderService.update(providerId, PlatformToolsSupport.userId(), command));
        return toDetail(provider);
    }

    @Tool(name = "delete_llm_provider",
            description = "Delete one of your LLM providers. Cascades to its agent bindings (agents "
                    + "fall back to the platform model) and deletes the stored key. The platform "
                    + "provider cannot be deleted — disable it instead",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteLlmProvider(@ToolParam("Provider public ID") String id) {
        UUID providerId = PlatformToolsSupport.parseUuid(id, "id");
        PlatformToolsSupport.domain(() -> {
            llmProviderService.delete(providerId, PlatformToolsSupport.userId(), false);
            return null;
        });
        return new OperationResult(true, "LLM provider deleted");
    }

    // ---- provider models -------------------------------------------------------------------

    @Tool(name = "refresh_llm_provider_models",
            description = "Sync the provider's model registry from the provider's /models endpoint "
                    + "using the stored key (this also validates the key). Models that disappeared "
                    + "become UNAVAILABLE rather than being deleted. An empty listing leaves statuses "
                    + "alone. Returns the registry after the refresh",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false),
            timeoutSeconds = 120)
    public LlmProviderModelList refreshLlmProviderModels(@ToolParam("Provider public ID") String id) {
        UUID providerId = PlatformToolsSupport.parseUuid(id, "id");
        PlatformToolsSupport.domain(
                () -> llmProviderService.requireOwned(providerId, PlatformToolsSupport.userId()));
        // The controller-DTO return is discarded — the registry is re-read below.
        PlatformToolsSupport.domain(() -> {
            llmProviderService.refreshModels(providerId, PlatformToolsSupport.userId(), false);
            return null;
        });
        return listModels(providerId);
    }

    @Tool(name = "list_llm_provider_models",
            description = "List the provider's model registry: discovery metadata, availability "
                    + "status and per-model extra body. Refresh it with refresh_llm_provider_models",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public LlmProviderModelList listLlmProviderModels(@ToolParam("Provider public ID") String id) {
        UUID providerId = PlatformToolsSupport.parseUuid(id, "id");
        PlatformToolsSupport.domain(
                () -> llmProviderService.requireOwned(providerId, PlatformToolsSupport.userId()));
        return listModels(providerId);
    }

    @Tool(name = "list_llm_provider_catalog",
            description = "The catalogue of known LLM gateways used to prefill the create form: exact "
                    + "baseUrl form, provider type, models to start from and where to get a key. Use "
                    + "before create_llm_provider",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public LlmProviderCatalogList listLlmProviderCatalog() {
        List<LlmProviderCatalogEntry> items = llmProviderCatalogRepository
                .findByEnabledTrueOrderBySortOrderAscNameAsc().stream()
                .map(this::toCatalogEntry)
                .toList();
        return new LlmProviderCatalogList(items);
    }

    // ---- quotas ----------------------------------------------------------------------------

    @Tool(name = "list_llm_quotas",
            description = "List the token quotas of one of your LLM providers: per subject (USER, "
                    + "AGENT, TOTAL — the whole provider's ceiling) and per calendar window (DAY, "
                    + "MONTH)",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public LlmQuotaList listLlmQuotas(@ToolParam("Provider public ID") String providerId) {
        UUID id = requireOwnedProvider(providerId);
        List<LlmQuotaItem> items = llmQuotaService.listForProvider(id).stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .map(this::toQuotaItem)
                .toList();
        return new LlmQuotaList(items, items.size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "create_llm_quota",
            description = "Create a token quota on one of your LLM providers. subjectKind: USER, "
                    + "AGENT or TOTAL (the whole provider's ceiling); window: DAY or MONTH. Refused "
                    + "with a conflict when a quota for the same subject and window already exists — "
                    + "update it instead",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public LlmQuotaItem createLlmQuota(
            @ToolParam("Provider public ID") String providerId,
            @ToolParam("Subject: USER, AGENT or TOTAL") String subjectKind,
            @ToolParam("Window: DAY or MONTH") String window,
            @ToolParam("Token limit per window") Long limitTokens) {
        UUID id = requireOwnedProvider(providerId);
        Long limit = requireLimit(limitTokens);
        LlmQuota quota = PlatformToolsSupport.domain(() -> llmQuotaService.create(id,
                requireEnum(UsageSubjectKind.class, subjectKind, "subjectKind"),
                requireEnum(UsageWindow.class, window, "window"),
                limit));
        return toQuotaItem(quota);
    }

    @Tool(name = "update_llm_quota",
            description = "Update the token limit of one of your LLM provider's quotas",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public LlmQuotaItem updateLlmQuota(
            @ToolParam("Provider public ID") String providerId,
            @ToolParam("Quota public ID") String quotaId,
            @ToolParam("New token limit per window") Long limitTokens) {
        UUID id = requireOwnedProvider(providerId);
        LlmQuota quota = PlatformToolsSupport.domain(() -> llmQuotaService.updateLimit(id,
                PlatformToolsSupport.parseUuid(quotaId, "quotaId"), requireLimit(limitTokens)));
        return toQuotaItem(quota);
    }

    @Tool(name = "delete_llm_quota",
            description = "Delete a token quota of one of your LLM providers; that subject and window "
                    + "become unlimited again",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteLlmQuota(
            @ToolParam("Provider public ID") String providerId,
            @ToolParam("Quota public ID") String quotaId) {
        UUID id = requireOwnedProvider(providerId);
        PlatformToolsSupport.domain(() -> {
            llmQuotaService.delete(id, PlatformToolsSupport.parseUuid(quotaId, "quotaId"));
            return null;
        });
        return new OperationResult(true, "Quota deleted");
    }

    // ---- agent LLM bindings ----------------------------------------------------------------

    @Tool(name = "list_agent_llms",
            description = "List the LLM bindings of an agent you own: which provider and model serve "
                    + "each purpose (CHAT, IMAGE, VISION, AUDIO_IN, AUDIO_OUT). An agent with no "
                    + "bindings runs on the platform model",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public AgentLlmBindingList listAgentLlms(@ToolParam("Agent public ID") String agentId) {
        // Read-only listing — no self-guard, same as list_agent_skills; owner scope stays.
        UUID id = PlatformToolsSupport.ownedAgent(agentRepository, PlatformToolsSupport.parseUuid(agentId, "agentId")).getId();
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByPurpose(id).stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .toList();
        return new AgentLlmBindingList(toAgentLlmBindings(bindings), toAgentLlmBindings(bindings).size() == PlatformToolsSupport.MAX_LISTING);
    }

    @Tool(name = "set_agent_llm",
            description = "Set the LLM binding of a purpose on an agent you own: creates the binding "
                    + "or replaces the existing one for that purpose. purpose defaults to CHAT (the "
                    + "agent-loop model); IMAGE, VISION, AUDIO_IN and AUDIO_OUT are media "
                    + "model-as-tool bindings. The model must exist in the provider's model registry "
                    + "if it is non-empty",
            annotations = @ToolAnnotations(destructiveHint = false, openWorldHint = false))
    public AgentLlmBinding setAgentLlm(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("LLM provider public ID (from list_llm_providers)") String providerId,
            @ToolParam("Model name (must exist in the provider's model registry if non-empty)") String model,
            @ToolParam(value = "Purpose: CHAT (default), IMAGE, VISION, AUDIO_IN, AUDIO_OUT",
                    required = false) String purpose) {
        UUID agent = requireOwnedAgent(agentId);
        UUID provider = PlatformToolsSupport.parseUuid(providerId, "providerId");
        LlmPurpose requested = optionalEnum(LlmPurpose.class, purpose, "purpose");
        LlmPurpose resolved = requested != null ? requested : LlmPurpose.CHAT;
        String modelName = PlatformToolsSupport.requireText(model, "model");
        boolean exists = agentLlmRepository.findAllByAgentIdOrderByPurpose(agent).stream()
                .anyMatch(binding -> binding.getPurpose() == resolved);
        AgentLlm binding = PlatformToolsSupport.domain(() -> {
            try {
                return exists
                        ? agentLlmService.replace(agent, PlatformToolsSupport.userId(), resolved, provider, modelName)
                        : agentLlmService.create(agent, PlatformToolsSupport.userId(), provider, modelName, resolved);
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // A concurrent set for the same purpose won the create race — a retry takes the
                // replace branch. Surface it as a clean error, not a raw persistence exception.
                throw new ConnectorException("LLM binding for this purpose already exists — retry");
            }
        });
        return toAgentLlmBinding(binding);
    }

    @Tool(name = "delete_agent_llm",
            description = "Remove the LLM binding of a given purpose from an agent you own; that "
                    + "purpose falls back to the platform default resolution",
            annotations = @ToolAnnotations(destructiveHint = true, openWorldHint = false))
    public OperationResult deleteAgentLlm(
            @ToolParam("Agent public ID") String agentId,
            @ToolParam("Purpose whose binding is removed: CHAT, IMAGE, VISION, AUDIO_IN, AUDIO_OUT")
            String purpose) {
        UUID agent = requireOwnedAgent(agentId);
        PlatformToolsSupport.domain(() -> {
            agentLlmService.delete(agent, PlatformToolsSupport.userId(),
                    PlatformToolsSupport.parseEnum(LlmPurpose.class, purpose, "purpose"));
            return null;
        });
        return new OperationResult(true, "LLM binding deleted");
    }

    // ---- usage -----------------------------------------------------------------------------

    @Tool(name = "get_llm_usage",
            description = "Your token usage and remaining quota per provider, for the current DAY and "
                    + "MONTH windows. Own (BYOK) providers show whole-provider usage; the platform "
                    + "provider shows your usage",
            annotations = @ToolAnnotations(readOnlyHint = true, idempotentHint = true, openWorldHint = false))
    public LlmUsageList getLlmUsage() {
        List<LlmUsageItem> items = llmUsageQueryService
                .usageForUserSnapshot(PlatformToolsSupport.userId()).stream()
                .map(this::toUsageItem)
                .toList();
        return new LlmUsageList(items);
    }

    // ---- guards ----------------------------------------------------------------------------

    /** The provider-scoped guard of every quota tool: own or 404 (non-admin — the platform row is invisible). */
    private UUID requireOwnedProvider(String providerId) {
        UUID id = PlatformToolsSupport.parseUuid(providerId, "providerId");
        PlatformToolsSupport.domain(() -> llmProviderService.requireOwned(id, PlatformToolsSupport.userId()));
        return id;
    }

    /**
     * The agent-scoped guard of the agent-LLM tools: an agent does not manage itself, and only the
     * owner's agents are reachable (the repository methods have no user filter — without this check
     * the bindings of another user's agent would leak).
     */
    private UUID requireOwnedAgent(String agentId) {
        UUID id = PlatformToolsSupport.parseUuid(agentId, "agentId");
        PlatformToolsSupport.requireNotSelf(id);
        PlatformToolsSupport.ownedAgent(agentRepository, id);
        return id;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private static <E extends Enum<E>> E requireEnum(Class<E> type, String value, String field) {
        return PlatformToolsSupport.parseEnum(type, PlatformToolsSupport.requireText(value, field), field);
    }

    /** A quota limit is mandatory — a null (absent) primitive would otherwise 500 inside reflection. */
    private static long requireLimit(Long limitTokens) {
        if (limitTokens == null) {
            throw new ConnectorException("Parameter 'limitTokens' is required");
        }
        return limitTokens;
    }

    private static <E extends Enum<E>> E optionalEnum(Class<E> type, String value, String field) {
        return PlatformToolsSupport.blankToNull(value) == null ? null
                : PlatformToolsSupport.parseEnum(type, value, field);
    }

    /** Converts the LLM-facing {@code Map<String, List<String>>} into the domain's keyed-by-enum map. */
    private static Map<LlmPurpose, List<String>> toPurposePriority(Map<String, List<String>> purposePriority) {
        if (purposePriority == null) {
            return null;
        }
        Map<LlmPurpose, List<String>> result = new LinkedHashMap<>();
        purposePriority.forEach((key, models) -> result.put(
                PlatformToolsSupport.parseEnum(LlmPurpose.class, key, "purposePriority"), models));
        return result;
    }

    private LlmProviderBrief toBrief(LlmProvider p) {
        return new LlmProviderBrief(p.getId().toString(), p.getName(), p.getProviderType().name(),
                p.getBaseUrl(), p.getApiKeyMask(), p.isEnabled(),
                p.getModelsRefreshedAt() != null ? p.getModelsRefreshedAt().toString() : null);
    }

    private LlmProviderDetail toDetail(LlmProvider p) {
        return new LlmProviderDetail(p.getId().toString(), p.getName(), p.getProviderType().name(),
                p.getBaseUrl(), p.getApiKeyMask(), p.isEnabled(),
                p.getModelsRefreshedAt() != null ? p.getModelsRefreshedAt().toString() : null,
                toPurposeNames(p.getPurposePriority()), p.getExtraBody(),
                p.getMediaTransport() != null ? p.getMediaTransport().name() : null);
    }

    private static Map<String, List<String>> toPurposeNames(Map<LlmPurpose, List<String>> purposePriority) {
        if (purposePriority == null) {
            return null;
        }
        return purposePriority.entrySet().stream().collect(Collectors.toMap(
                e -> e.getKey().name(), Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private LlmProviderModelList listModels(UUID providerId) {
        List<LlmProviderModel> models = llmProviderModelRepository
                .findAllByLlmProviderIdOrderByModel(providerId).stream()
                .limit(PlatformToolsSupport.MAX_LISTING)
                .map(this::toModel)
                .toList();
        return new LlmProviderModelList(models, models.size() == PlatformToolsSupport.MAX_LISTING);
    }

    private LlmProviderModel toModel(ru.agimate.controlapi.database.entities.LlmProviderModel m) {
        return new LlmProviderModel(m.getId().toString(), m.getModel(), m.getDisplayName(),
                m.getContextWindow(), m.getMaxOutputTokens(), m.getInputModalities(),
                m.getOutputModalities(), m.getStatus().name(), m.getExtraBody());
    }

    private LlmProviderCatalogEntry toCatalogEntry(
            ru.agimate.controlapi.database.entities.LlmProviderCatalogEntry e) {
        return new LlmProviderCatalogEntry(e.getCode(), e.getName(), e.getDescription(),
                e.getProviderType().name(), e.getBaseUrl(),
                e.getMediaTransport() != null ? e.getMediaTransport().name() : null,
                toPurposeNames(e.getPurposePriority()), e.getApiKeyUrl(), e.isEnabled());
    }

    private LlmQuotaItem toQuotaItem(LlmQuota q) {
        return new LlmQuotaItem(q.getId().toString(), q.getSubjectKind().name(), q.getWindow().name(),
                q.getLimitTokens());
    }

    private List<AgentLlmBinding> toAgentLlmBindings(List<AgentLlm> bindings) {
        if (bindings.isEmpty()) {
            return List.of();
        }
        Map<UUID, LlmProvider> providersById = llmProviderRepository
                .findAllByIdIn(bindings.stream().map(AgentLlm::getLlmProviderId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(LlmProvider::getId, Function.identity()));
        return bindings.stream().map(b -> toAgentLlmBinding(b, providersById)).toList();
    }

    private AgentLlmBinding toAgentLlmBinding(AgentLlm binding) {
        LlmProvider provider = llmProviderRepository.findById(binding.getLlmProviderId()).orElse(null);
        return new AgentLlmBinding(binding.getPurpose().name(), binding.getLlmProviderId().toString(),
                provider != null ? provider.getName() : null, binding.getModel());
    }

    private static AgentLlmBinding toAgentLlmBinding(AgentLlm binding, Map<UUID, LlmProvider> providersById) {
        LlmProvider provider = providersById.get(binding.getLlmProviderId());
        return new AgentLlmBinding(binding.getPurpose().name(), binding.getLlmProviderId().toString(),
                provider != null ? provider.getName() : null, binding.getModel());
    }

    private LlmUsageItem toUsageItem(LlmUsageSnapshot s) {
        List<LlmUsageWindow> windows = s.windows().stream()
                .map(w -> new LlmUsageWindow(w.window().name(), w.windowStart().toString(),
                        w.usedTokens(), w.requests(), w.limitTokens(), w.remainingTokens()))
                .toList();
        return new LlmUsageItem(
                s.llmProviderId() != null ? s.llmProviderId().toString() : null,
                s.providerName(), s.source(), windows);
    }
}
