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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single pipeline for issuing LLM credentials: provider/model selection → the {@code enabled}
 * check → the quota → key decryption → the {@code extra_body} deep merge. Its consumers are the
 * worker's gRPC path ({@code GetLlmCredentials} → {@link #resolveChat}) and the connector's media
 * path ({@link #resolveForCapability}). The logic lives in one place so the paths do not drift apart
 * on quotas and extra_body.
 *
 * <p>The model is never guessed. Outside an explicit {@code agent_llms} binding it comes from the
 * provider's {@code purpose_priority} allowlist and nowhere else: a purpose the user has not
 * configured produces a message addressed to them, not a capability search through the registry that
 * silently spends their money on a model they never chose.
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
     * The modalities come from the model's registry row ({@code llm_provider_models}, with the
     * {@code llm_model_defaults} fallback merged in at write time); an empty list means the model is
     * unknown to the registry — «not declared», never «cannot». {@code modelMetadata} is that row's
     * raw listing entry, in the provider's own shape: only a transport built for that provider may
     * read it, and only it knows what the shape means.
     *
     * @param platformFallback {@code true} — the platform provider was issued (the agent has no binding)
     */
    public record ResolvedLlm(
            LlmProvider provider,
            String model,
            String apiKey,
            Map<String, Object> extraBody,
            List<String> inputModalities,
            List<String> outputModalities,
            Map<String, Object> modelMetadata,
            boolean platformFallback) {
    }

    /**
     * The chat model of the agent loop: the agent's {@code purpose = CHAT} binding (there is exactly
     * one — uniqueness on {@code (agent_id, purpose)}), otherwise a fallback to the platform provider
     * and the first live model of its {@code CHAT} list (a personal binding always wins). Tool
     * bindings (IMAGE/VISION/…) never reach here.
     *
     * @throws NotFoundStatusException      the binding's provider is gone / its model is no longer listed / there is no binding and the platform cannot serve CHAT
     * @throws LlmProviderDisabledException the binding's provider is disabled
     * @throws QuotaExceededException       the provider's quota is exhausted
     */
    public ResolvedLlm resolveChat(UUID agentId, UUID userId) {
        LlmProvider provider;
        Pick pick;
        boolean platformFallback = false;

        AgentLlm binding = agentLlmRepository
                .findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT)
                .orElse(null);
        if (binding != null) {
            provider = boundProvider(binding);
            pick = boundPick(provider, binding);
            if (isGone(pick.row())) {
                throw new NotFoundStatusException(boundModelGone(provider, LlmPurpose.CHAT, pick.model()));
            }
        } else {
            provider = llmProviderService.findUsablePlatformProvider()
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No LLM binding for agent " + agentId + " and no platform provider available"));
            LlmProvider platform = provider;
            pick = pickModel(platform, LlmPurpose.CHAT)
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No chat model available for agent " + agentId + ": "
                                    + miss(platform, LlmPurpose.CHAT)));
            platformFallback = true;
        }

        // Before every LLM call (the credentials are requested inline on each llm_call).
        llmQuotaService.check(provider, userId, agentId);

        return resolved(provider, pick, platformFallback);
    }

    /**
     * A tool model for a purpose (the media connector). The cascade: an explicit agent binding with
     * that {@code purpose} (it always wins — the lists are not consulted at all) → the
     * {@code purpose_priority} list of the provider behind the agent's chat binding (media follows the
     * billing the user has already chosen) → the same list on the platform provider. No search by
     * modality: if neither declares the purpose, the caller is told so.
     *
     * @throws IllegalArgumentException     {@code purpose == CHAT} — the chat model is issued by {@link #resolveChat}
     * @throws NoCapableModelException      the bound model is no longer listed, or no provider of the chain declares a usable one
     * @throws LlmProviderDisabledException the explicit binding's provider is disabled (loudly, with no fallback)
     * @throws QuotaExceededException       the provider's quota is exhausted
     */
    public ResolvedLlm resolveForCapability(UUID agentId, UUID userId, LlmPurpose purpose) {
        if (purpose == LlmPurpose.CHAT) {
            throw new IllegalArgumentException("CHAT is resolved via resolveChat");
        }
        LlmProvider provider;
        Pick pick;
        boolean platformFallback = false;

        AgentLlm binding = agentLlmRepository
                .findByAgentIdAndPurpose(agentId, purpose)
                .orElse(null);
        if (binding != null) {
            provider = boundProvider(binding);
            pick = boundPick(provider, binding);
            if (isGone(pick.row())) {
                throw new NoCapableModelException(boundModelGone(provider, purpose, pick.model()));
            }
        } else {
            Candidate candidate = fromChain(agentId, purpose);
            provider = candidate.provider();
            pick = candidate.pick();
            platformFallback = LlmProviderService.isPlatform(provider);
        }

        llmQuotaService.check(provider, userId, agentId);

        return resolved(provider, pick, platformFallback);
    }

    private record Candidate(LlmProvider provider, Pick pick) {
    }

    /**
     * A chosen model together with its registry row, carried to the end of the resolution so the row
     * is read once: the same row answers «is the model still listed», {@code extra_body} and
     * {@code input_modalities}, and resolveChat runs inline on every llm_call.
     *
     * @param row {@code null} — the model is not in the registry at all (configured before the first
     *            discovery, which is legitimate and not an error)
     */
    private record Pick(String model, LlmProviderModel row) {
    }

    /**
     * The first provider of the chain that declares a usable model for the purpose. The failure
     * message carries the reason for every provider tried, because «no vision model» has three very
     * different fixes depending on whether the list is absent, empty or dead. An empty list stops the
     * chain: it is an explicit «off», not a gap for the next provider to fill.
     */
    private Candidate fromChain(UUID agentId, LlmPurpose purpose) {
        List<LlmProvider> chain = capabilityChain(agentId);
        List<String> misses = new ArrayList<>();
        for (LlmProvider provider : chain) {
            Optional<Pick> pick = pickModel(provider, purpose);
            if (pick.isPresent()) {
                return new Candidate(provider, pick.get());
            }
            misses.add(miss(provider, purpose));
            if (provider.modelsFor(purpose).map(List::isEmpty).orElse(false)) {
                // An explicit empty list is a decision to keep the purpose off; walking on to the
                // platform would quietly undo it. A missing key is a gap and does continue.
                break;
            }
        }
        String reason = misses.isEmpty()
                ? "the agent has no chat provider and there is no platform provider"
                : String.join("; ", misses);
        throw new NoCapableModelException("No " + purpose + " model available: " + reason
                + ". Bind a " + purpose + " model to this agent or add one to the provider's "
                + purpose + " list.");
    }

    /**
     * Where a tool model is looked for, in order: the provider of the agent's chat binding, then the
     * platform one. Disabled providers drop out of the chain rather than failing it — the media tool
     * is not the place to discover that the chat provider was switched off.
     */
    private List<LlmProvider> capabilityChain(UUID agentId) {
        List<LlmProvider> chain = new ArrayList<>();
        agentLlmRepository.findByAgentIdAndPurpose(agentId, LlmPurpose.CHAT)
                .map(AgentLlm::getLlmProviderId)
                .flatMap(llmProviderRepository::findById)
                .filter(LlmProvider::isEnabled)
                .ifPresent(chain::add);
        llmProviderService.findUsablePlatformProvider()
                .filter(platform -> chain.stream().noneMatch(p -> p.getId().equals(platform.getId())))
                .ifPresent(chain::add);
        return chain;
    }

    /**
     * The first model of the provider's list for the purpose that is not known to be gone: the
     * declared order is the user's, UNAVAILABLE registry rows are skipped (a declared list is also a
     * fallback chain), and a model with no registry row at all is taken as-is — configuring one before
     * the first discovery is legitimate.
     */
    private Optional<Pick> pickModel(LlmProvider provider, LlmPurpose purpose) {
        List<String> declared = provider.modelsFor(purpose).orElse(List.of());
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        Map<String, LlmProviderModel> registry = llmProviderModelRepository
                .findAllByLlmProviderIdOrderByModel(provider.getId()).stream()
                .collect(Collectors.toMap(LlmProviderModel::getModel, Function.identity()));
        return declared.stream()
                .map(model -> new Pick(model, registry.get(model)))
                .filter(pick -> !isGone(pick.row()))
                .findFirst();
    }

    /** The bound model plus its registry row — the binding names one model, so it is a direct lookup. */
    private Pick boundPick(LlmProvider provider, AgentLlm binding) {
        return new Pick(binding.getModel(), llmProviderModelRepository
                .findByLlmProviderIdAndModel(provider.getId(), binding.getModel())
                .orElse(null));
    }

    /** Known to the registry and gone from the provider's last listing. A model it never saw is not «gone». */
    private static boolean isGone(LlmProviderModel row) {
        return row != null && row.getStatus() == LlmProviderModelStatus.UNAVAILABLE;
    }

    /**
     * A binding whose model has dropped out of the listing. There is no fallback here on purpose: the
     * model was named by a human, and quietly answering from a different one would be the guess this
     * whole path exists to avoid.
     */
    private static String boundModelGone(LlmProvider provider, LlmPurpose purpose, String model) {
        return "Model '" + model + "' bound to this agent for " + purpose
                + " is no longer listed by provider '" + provider.getName()
                + "'. Rebind the agent or refresh the provider's models.";
    }

    /** Why this provider cannot serve the purpose — user-facing text, not a log line. */
    private String miss(LlmProvider provider, LlmPurpose purpose) {
        Optional<List<String>> declared = provider.modelsFor(purpose);
        if (declared.isEmpty()) {
            return "provider '" + provider.getName() + "' has no models configured for " + purpose;
        }
        if (declared.get().isEmpty()) {
            return purpose + " is switched off on provider '" + provider.getName() + "' (empty list)";
        }
        return "all " + purpose + " models of provider '" + provider.getName()
                + "' are unavailable: " + declared.get();
    }

    private LlmProvider boundProvider(AgentLlm binding) {
        LlmProvider provider = llmProviderRepository.findById(binding.getLlmProviderId())
                .orElseThrow(() -> new NotFoundStatusException(
                        "LLM provider not found: " + binding.getLlmProviderId()));
        if (!provider.isEnabled()) {
            throw new LlmProviderDisabledException("LLM provider disabled");
        }
        return provider;
    }

    /** Finalisation: key decryption plus the extra_body merge and the modalities of the picked row. */
    private ResolvedLlm resolved(LlmProvider provider, Pick pick, boolean platformFallback) {
        String apiKey = llmProviderService.decryptApiKey(provider);
        LlmProviderModel row = pick.row();
        Map<String, Object> extraBody = ExtraBodyMerge.merge(provider.getExtraBody(),
                row != null ? row.getExtraBody() : null);
        Map<String, Object> metadata = row == null || row.getRawMetadata() == null
                ? Map.of() : row.getRawMetadata();
        return new ResolvedLlm(provider, pick.model(), apiKey, extraBody,
                modalities(row == null ? null : row.getInputModalities()),
                modalities(row == null ? null : row.getOutputModalities()),
                metadata, platformFallback);
    }

    /** An absent registry row or an absent field are the same thing for the caller: nothing declared. */
    private static List<String> modalities(List<String> declared) {
        return declared == null ? List.of() : List.copyOf(declared);
    }

}
