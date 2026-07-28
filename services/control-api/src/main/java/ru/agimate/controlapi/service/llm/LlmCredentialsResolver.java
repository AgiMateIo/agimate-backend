package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.service.LlmProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The single pipeline for issuing LLM credentials: provider/model selection → the {@code enabled}
 * check → the quota → key decryption → the {@code extra_body} deep merge. Its consumers are the
 * worker's gRPC path ({@code GetLlmCredentials} → {@link #resolveChat}) and the connector's media
 * path ({@link #resolveForCapability}). The logic lives in one place so the paths do not drift apart
 * on quotas and extra_body.
 *
 * <p>Not {@code @Transactional}: it joins the caller's read-only transaction. The key is decrypted at
 * call time and leaves only inside the return value — the caller must not persist it (into DBOS
 * checkpoints included).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCredentialsResolver {

    private final AgentLlmRepository agentLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderModelRepository llmProviderModelRepository;
    private final LlmProviderService llmProviderService;
    private final LlmQuotaService llmQuotaService;

    /**
     * The resolution's result. {@code extraBody} is the final deep merge of the provider-level and the
     * per-model one (the model wins, see {@link ExtraBodyMerge}); an empty map means no extra fields.
     * {@code inputModalities} comes from the model's registry row ({@code llm_provider_models}, with
     * the {@code llm_model_defaults} fallback merged in at write time); an empty list means the model
     * is unknown to the registry.
     *
     * @param platformFallback {@code true} — the platform provider was issued (the agent has no binding)
     */
    public record ResolvedLlm(
            LlmProvider provider,
            String model,
            String apiKey,
            Map<String, Object> extraBody,
            List<String> inputModalities,
            boolean platformFallback) {
    }

    /**
     * The chat model of the agent loop: the agent's {@code purpose = CHAT} binding (there is exactly
     * one — uniqueness on {@code (agent_id, purpose)}), otherwise a fallback to the platform provider
     * with its {@code default_model} (a personal binding always wins). Tool bindings (IMAGE/VISION/…)
     * never reach here.
     *
     * @throws NotFoundStatusException      the binding's provider is gone / there is neither a binding nor a platform one
     * @throws LlmProviderDisabledException the binding's provider is disabled
     * @throws QuotaExceededException       the provider's quota is exhausted
     */
    public ResolvedLlm resolveChat(UUID agentId, UUID userId) {
        LlmProvider provider;
        String model;
        boolean platformFallback = false;

        AgentLlm binding = agentLlmRepository
                .findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT)
                .orElse(null);
        if (binding != null) {
            provider = llmProviderRepository.findById(binding.getLlmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + binding.getLlmProviderId()));
            if (!provider.isEnabled()) {
                throw new LlmProviderDisabledException("LLM provider disabled");
            }
            model = binding.getModel();
        } else {
            // The fallback: the platform provider (already filtered by enabled + default_model).
            provider = llmProviderService.findUsablePlatformProvider()
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No LLM binding for agent: " + agentId));
            model = provider.getDefaultModel();
            platformFallback = true;
        }

        // Before every LLM call (the credentials are requested inline on each llm_call).
        llmQuotaService.check(provider, userId, agentId);

        return resolved(provider, model, platformFallback);
    }

    /**
     * A tool model for a purpose (the media connector). The cascade: an explicit agent binding with
     * that {@code purpose} (it always wins, and the registry is not consulted — the user's choice is
     * advisory, as in {@code AgentLlmService.validateModel}) → a capability match against the registry
     * of the user's enabled providers (the chat binding's provider first — fewer surprises with
     * billing; within a provider, the first model by name with status {@code AVAILABLE} and the
     * required modality) → the same match against the platform provider.
     *
     * @throws IllegalArgumentException     {@code purpose == CHAT} — the chat model is issued by {@link #resolveChat}
     * @throws NoCapableModelException      no binding and no suitable model in either registry
     * @throws LlmProviderDisabledException the explicit binding's provider is disabled (loudly, with no fallback)
     * @throws QuotaExceededException       the provider's quota is exhausted
     */
    public ResolvedLlm resolveForCapability(UUID agentId, UUID userId, LlmPurpose purpose) {
        ModalityRequirement requirement = requirement(purpose);
        LlmProvider provider;
        String model;
        boolean platformFallback = false;

        AgentLlm binding = agentLlmRepository
                .findByAgentIdAndPurpose(agentId, purpose)
                .orElse(null);
        if (binding != null) {
            provider = llmProviderRepository.findById(binding.getLlmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + binding.getLlmProviderId()));
            if (!provider.isEnabled()) {
                throw new LlmProviderDisabledException("LLM provider disabled");
            }
            model = binding.getModel();
        } else {
            Candidate candidate = findCapableModel(agentId, userId, requirement)
                    .orElseThrow(() -> new NoCapableModelException(
                            "No model capable of " + requirement.describe() + " is available: bind one "
                                    + "to the agent (purpose " + purpose + ") or add a provider "
                                    + "whose registry lists such a model"));
            provider = candidate.provider();
            model = candidate.model();
            platformFallback = candidate.platform();
        }

        llmQuotaService.check(provider, userId, agentId);

        return resolved(provider, model, platformFallback);
    }

    /** Finalisation: key decryption plus one lookup of the registry row (extra_body and the modalities). */
    private ResolvedLlm resolved(LlmProvider provider, String model, boolean platformFallback) {
        String apiKey = llmProviderService.decryptApiKey(provider);
        LlmProviderModel registryRow = model == null ? null : llmProviderModelRepository
                .findByProviderIdAndModel(provider.getId(), model)
                .orElse(null);
        Map<String, Object> extraBody = ExtraBodyMerge.merge(provider.getExtraBody(),
                registryRow != null ? registryRow.getExtraBody() : null);
        List<String> inputModalities = registryRow != null && registryRow.getInputModalities() != null
                ? List.copyOf(registryRow.getInputModalities()) : List.of();
        return new ResolvedLlm(provider, model, apiKey, extraBody, inputModalities, platformFallback);
    }

    private record Candidate(LlmProvider provider, String model, boolean platform) {
    }

    /** A requirement on the model: a modality on the input or on the output ({@code input/output_modalities}). */
    private record ModalityRequirement(String modality, boolean output) {

        String describe() {
            return (output ? "generating " : "reading ") + modality;
        }
    }

    private static ModalityRequirement requirement(LlmPurpose purpose) {
        return switch (purpose) {
            case IMAGE -> new ModalityRequirement("image", true);
            case VISION -> new ModalityRequirement("image", false);
            case AUDIO_IN -> new ModalityRequirement("audio", false);
            case AUDIO_OUT -> new ModalityRequirement("audio", true);
            case CHAT -> throw new IllegalArgumentException("CHAT is resolved via resolveChat");
        };
    }

    private Optional<Candidate> findCapableModel(UUID agentId, UUID userId, ModalityRequirement requirement) {
        List<LlmProvider> candidates = new ArrayList<>(llmProviderRepository
                .findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter(LlmProvider::isEnabled)
                .toList());
        // The chat binding's provider goes to the head of the list: media calls default to the same place the
        // user has already directed the agent's main billing.
        agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT)
                .map(AgentLlm::getLlmProviderId)
                .ifPresent(chatProviderId -> candidates.stream()
                        .filter(p -> p.getId().equals(chatProviderId))
                        .findFirst()
                        .ifPresent(p -> {
                            candidates.remove(p);
                            candidates.add(0, p);
                        }));

        for (LlmProvider provider : candidates) {
            Optional<String> model = firstCapableModel(provider, requirement);
            if (model.isPresent()) {
                return Optional.of(new Candidate(provider, model.get(), false));
            }
        }
        return llmProviderService.findUsablePlatformProvider()
                .flatMap(platform -> firstCapableModel(platform, requirement)
                        .map(model -> new Candidate(platform, model, true)));
    }

    private Optional<String> firstCapableModel(LlmProvider provider, ModalityRequirement requirement) {
        return llmProviderModelRepository.findAllByProviderIdOrderByModel(provider.getId()).stream()
                .filter(m -> m.getStatus() == LlmProviderModelStatus.AVAILABLE)
                .filter(m -> {
                    List<String> modalities = requirement.output()
                            ? m.getOutputModalities() : m.getInputModalities();
                    return modalities != null && modalities.contains(requirement.modality());
                })
                .map(LlmProviderModel::getModel)
                .findFirst();
    }

}
