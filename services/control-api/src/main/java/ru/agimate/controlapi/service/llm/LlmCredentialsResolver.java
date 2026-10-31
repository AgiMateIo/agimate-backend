package ru.agimate.controlapi.service.llm;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.LlmProviderModel;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.LlmProviderModelStatus;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.enums.LlmSource;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderModelRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.database.repositories.PlatformLlmRepository;
import ru.agimate.controlapi.service.LlmProviderService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single pipeline for issuing LLM credentials: chain resolution → eligibility → the quota → key
 * decryption → the {@code extra_body} deep merge. Consumers are the worker's gRPC path
 * ({@code GetLlmCredentials}), the connector's media path and the platform's routines — all through
 * {@link #resolveFor}, so the paths cannot drift apart on quotas and extra_body.
 *
 * <p>A purpose holds an ordered chain of bindings ({@code agent_llms.priority} or
 * {@code platform_llms.priority}) and the resolver takes the first <em>eligible</em> one — an
 * ineligible binding never stops the call while a later one is usable
 * (docs/decisions/agent-llm-purpose-chain.md). Only a rejection known before the provider is
 * contacted skips a binding: a disabled provider, an {@code UNAVAILABLE} model, an exhausted quota, a
 * model that cannot do the purpose's modality. A failed call is not retried on the next binding — it
 * may already have been paid for.
 *
 * <p>The agent's source decides the chain, and the chains never mix
 * (docs/decisions/platform-llm-purposes-and-agent-source.md): {@code PLATFORM} resolves only
 * {@code platform_llms}, {@code BYOK} only the agent's own bindings and the purpose lists of its chat
 * providers. An explicit binding set is a closed set — an exhausted user quota must not quietly spend
 * the platform's money, and a BYOK agent with no CHAT binding is a configured state that answers a
 * clear refusal, not a reason to fall back.
 *
 * <p>Not {@code @Transactional}: it only reads by id, so it needs no transaction of its own. The key is
 * decrypted at call time and leaves only inside the return value — the caller must not persist it
 * (into DBOS checkpoints included).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LlmCredentialsResolver {

    private final AgentRepository agentRepository;
    private final AgentLlmRepository agentLlmRepository;
    private final PlatformLlmRepository platformLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderModelRepository llmProviderModelRepository;
    private final LlmProviderService llmProviderService;
    private final LlmQuotaService llmQuotaService;

    /**
     * Ceiling on the registry's output limit. The registry keeps what the listing's top hoster allows
     * (up to hundreds of thousands of tokens), and asking for that much makes a stricter hoster refuse
     * the request or the router skip it; a longer answer is an explicit extra_body away.
     */
    static final int MAX_TOKENS_CAP = 32_768;

    /** The modality value in the registry ({@code architecture.input/output_modalities} of /models). */
    private static final String IMAGE_MODALITY = "image";

    /**
     * The resolution's result. {@code extraBody} is the final deep merge of the registry defaults
     * ({@link #registryDefaults}, text purposes only), the provider-level one and the per-model one,
     * each level winning over the one below (see {@link ExtraBodyMerge}); an empty map means no extra
     * fields. The modalities come from the model's registry row ({@code llm_provider_models}, with the
     * {@code llm_model_defaults} fallback merged in at write time); an empty list means the model is
     * unknown to the registry — «not declared», never «cannot». {@code modelMetadata} is that row's raw
     * listing entry, in the provider's own shape: only a transport built for that provider may read it,
     * and only it knows what the shape means.
     *
     * @param platformFallback {@code true} — the agent is on the platform source, so the platform pays
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
     * The model of a purpose — the first eligible binding of its chain. The chain is rebuilt before
     * every call, so a model may change mid-run when the current binding stops being eligible; that is
     * accepted, a dead binding being worse than a cold prompt cache.
     *
     * @throws QuotaExceededException       every candidate hit an exhausted quota; the text comes from
     *                                      {@link LlmQuotaService} and reaches the user verbatim
     * @throws LlmProviderDisabledException every candidate's provider is disabled
     * @throws NoCapableModelException      media: nothing eligible (its text is shown to the agent)
     * @throws NotFoundStatusException      CHAT/ROUTINE: nothing eligible (the worker shows its own text)
     */
    public ResolvedLlm resolveFor(UUID agentId, UUID userId, LlmPurpose purpose) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        Chain chain = chainFor(agent, purpose);
        Candidate pick = firstEligible(chain, purpose, userId, agentId, agent.getLlmSource());
        return toResolved(pick, purpose, agent.getLlmSource());
    }

    // ---- the chain -------------------------------------------------------------------------

    /** Candidates in the order they are tried plus the provider-level reasons collected on the way. */
    private record Chain(List<Candidate> candidates, List<String> misses) {
    }

    /** A model to try together with its registry row, read once and carried to the end. */
    private record Candidate(LlmProvider provider, String model, LlmProviderModel row) {
    }

    /**
     * The source, not the presence of bindings, decides the chain. {@code PLATFORM} reads only the
     * platform chains; {@code BYOK} reads only the agent's own bindings and its chat providers'
     * purpose lists. The two never mix.
     */
    private Chain chainFor(Agent agent, LlmPurpose purpose) {
        return agent.getLlmSource() == LlmSource.PLATFORM
                ? platformChain(purpose)
                : byokChain(agent.getId(), purpose);
    }

    /**
     * Platform chains, one per purpose. {@code ROUTINE} falls back to {@code CHAT}, exactly as an
     * agent's own ROUTINE does; media uses the purpose's own chain and nothing else.
     */
    private Chain platformChain(LlmPurpose purpose) {
        if (purpose == LlmPurpose.ROUTINE) {
            Chain routine = platformCandidates(LlmPurpose.ROUTINE);
            Chain chat = platformCandidates(LlmPurpose.CHAT);
            return new Chain(concat(routine.candidates(), chat.candidates()),
                    concat(routine.misses(), chat.misses()));
        }
        return platformCandidates(purpose);
    }

    /** The platform purpose's rows, each paired with its provider and registry row. */
    private Chain platformCandidates(LlmPurpose purpose) {
        List<PlatformLlm> rows = platformLlmRepository.findAllByPurposeOrderByPriorityAsc(purpose);
        if (rows.isEmpty()) {
            return new Chain(List.of(), List.of("the platform declares no " + purpose + " models"));
        }
        Map<UUID, LlmProvider> providersById = providersByIdPlatformRows(rows);
        List<Candidate> candidates = new ArrayList<>(rows.size());
        for (PlatformLlm row : rows) {
            LlmProvider provider = providersById.get(row.getLlmProviderId());
            if (provider == null) {
                continue;
            }
            // A disabled provider is skipped by eligibility before anything else — its registry row
            // would be read only to be discarded.
            LlmProviderModel modelRow = provider.isEnabled()
                    ? llmProviderModelRepository
                            .findByLlmProviderIdAndModel(provider.getId(), row.getModel())
                            .orElse(null)
                    : null;
            candidates.add(new Candidate(provider, row.getModel(), modelRow));
        }
        return new Chain(candidates, List.of());
    }

    private Chain byokChain(UUID agentId, LlmPurpose purpose) {
        List<AgentLlm> explicit = bindings(agentId, purpose);
        if (!explicit.isEmpty()) {
            return new Chain(explicitCandidates(explicit), List.of());
        }
        return switch (purpose) {
            case CHAT -> new Chain(List.of(),
                    List.of("the agent runs on BYOK and has no CHAT binding"));
            case ROUTINE -> byokRoutineChain(agentId);
            default -> byokMediaChain(agentId, purpose);
        };
    }

    private Chain byokRoutineChain(UUID agentId) {
        List<AgentLlm> chatBindings = bindings(agentId, LlmPurpose.CHAT);
        Chain lists = providerLists(chatProviders(chatBindings), LlmPurpose.ROUTINE);
        // The chore falls back to the model the agent already talks through — the platform is not
        // appended behind an agent that brought its own keys.
        Chain chat = new Chain(explicitCandidates(chatBindings), List.of());
        return new Chain(concat(lists.candidates(), chat.candidates()),
                concat(lists.misses(), chat.misses()));
    }

    private Chain byokMediaChain(UUID agentId, LlmPurpose purpose) {
        return providerLists(chatProviders(bindings(agentId, LlmPurpose.CHAT)), purpose);
    }

    /**
     * The providers of the agent's chat chain, enabled and in binding order. No chat bindings means no
     * chat providers: under BYOK the platform never steps in.
     */
    private List<LlmProvider> chatProviders(List<AgentLlm> chatBindings) {
        return providersInOrder(chatBindings).stream().filter(LlmProvider::isEnabled).toList();
    }

    /**
     * The declared purpose lists of the providers, in order. A missing key is a gap and the next
     * provider continues; an empty list is an explicit «off» on that provider — it drops it from the
     * chain, so one provider's {@code []} cannot close the purpose on the next one.
     */
    private Chain providerLists(List<LlmProvider> providers, LlmPurpose purpose) {
        List<Candidate> candidates = new ArrayList<>();
        List<String> misses = new ArrayList<>();
        for (LlmProvider provider : providers) {
            Optional<List<String>> declared = provider.modelsFor(purpose);
            if (declared.isEmpty() || declared.get().isEmpty()) {
                misses.add(miss(provider, purpose));
                continue;
            }
            Map<String, LlmProviderModel> registry = registryOf(provider);
            for (String model : declared.get()) {
                candidates.add(new Candidate(provider, model, registry.get(model)));
            }
        }
        return new Chain(candidates, misses);
    }

    /** The bound model plus its registry row — each binding names one model, so it is a direct lookup. */
    private List<Candidate> explicitCandidates(List<AgentLlm> bindings) {
        List<Candidate> result = new ArrayList<>(bindings.size());
        Map<UUID, LlmProvider> byId = providersById(bindings);
        for (AgentLlm binding : bindings) {
            LlmProvider provider = byId.get(binding.getLlmProviderId());
            if (provider == null) {
                continue;
            }
            LlmProviderModel row = provider.isEnabled()
                    ? llmProviderModelRepository
                            .findByLlmProviderIdAndModel(provider.getId(), binding.getModel())
                            .orElse(null)
                    : null;
            result.add(new Candidate(provider, binding.getModel(), row));
        }
        return result;
    }

    private List<AgentLlm> bindings(UUID agentId, LlmPurpose purpose) {
        return agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agentId, purpose);
    }

    private Map<UUID, LlmProvider> providersById(List<AgentLlm> bindings) {
        List<UUID> ids = bindings.stream().map(AgentLlm::getLlmProviderId).distinct().toList();
        return llmProviderRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(LlmProvider::getId, Function.identity()));
    }

    private Map<UUID, LlmProvider> providersByIdPlatformRows(List<PlatformLlm> bindings) {
        List<UUID> ids = bindings.stream().map(PlatformLlm::getLlmProviderId).distinct().toList();
        return llmProviderRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(LlmProvider::getId, Function.identity()));
    }

    private List<LlmProvider> providersInOrder(List<AgentLlm> bindings) {
        Map<UUID, LlmProvider> byId = providersById(bindings);
        return bindings.stream()
                .map(AgentLlm::getLlmProviderId)
                .distinct()
                .map(byId::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private Map<String, LlmProviderModel> registryOf(LlmProvider provider) {
        return llmProviderModelRepository.findAllByLlmProviderIdOrderByModel(provider.getId()).stream()
                .collect(Collectors.toMap(LlmProviderModel::getModel, Function.identity()));
    }

    // ---- eligibility -----------------------------------------------------------------------

    /**
     * Walks the chain and returns the first candidate that may be called. The quota check is the last
     * one — it is the only check that touches another service and the only one whose failure has a
     * user-facing text of its own. The type of the refusal names the common cause, because the worker
     * turns the gRPC status into what the human sees: all quotas exhausted → {@code RESOURCE_EXHAUSTED},
     * all providers off → {@code FAILED_PRECONDITION}, everything else → the media text or
     * {@code NOT_FOUND}.
     */
    private Candidate firstEligible(Chain chain, LlmPurpose purpose, UUID userId, UUID agentId,
                                    LlmSource source) {
        List<String> reasons = new ArrayList<>(chain.misses());
        List<String> quotaMessages = new ArrayList<>();
        int disabled = 0;
        int quota = 0;
        List<Candidate> candidates = chain.candidates();
        for (Candidate candidate : candidates) {
            LlmProvider provider = candidate.provider();
            if (!provider.isEnabled()) {
                reasons.add("provider '" + provider.getName() + "' is disabled");
                disabled++;
                continue;
            }
            if (isGone(candidate.row())) {
                reasons.add("model '" + candidate.model() + "' of provider '" + provider.getName()
                        + "' is no longer listed");
                continue;
            }
            if (!supportsPurpose(candidate.row(), purpose)) {
                reasons.add(modalityMiss(candidate, purpose));
                continue;
            }
            try {
                llmQuotaService.check(provider, userId, agentId);
            } catch (QuotaExceededException e) {
                quotaMessages.add(e.getMessage());
                reasons.add(e.getMessage());
                quota++;
                continue;
            }
            return candidate;
        }

        if (!candidates.isEmpty() && quota == candidates.size()) {
            throw new QuotaExceededException(String.join(" ", distinct(quotaMessages)));
        }
        if (!candidates.isEmpty() && disabled == candidates.size()) {
            String names = candidates.stream().map(c -> "'" + c.provider().getName() + "'")
                    .distinct().collect(Collectors.joining(", "));
            throw new LlmProviderDisabledException(
                    "All providers of the " + purpose + " chain are disabled: " + names);
        }
        throw refusal(purpose, reasons, agentId, source);
    }

    private RuntimeException refusal(LlmPurpose purpose, List<String> reasons, UUID agentId,
                                     LlmSource source) {
        String detail = reasons.isEmpty()
                ? "no binding is configured for " + purpose + " and no provider declares a model for it"
                : String.join("; ", reasons);
        if (!purpose.isText()) {
            String advice = source == LlmSource.BYOK
                    ? " Bind a " + purpose + " model to this agent or add one to the provider's "
                    + purpose + " list. Or switch the agent to the platform model source."
                    : " The platform admin must add a " + purpose + " model to the platform chain.";
            return new NoCapableModelException(
                    "No " + purpose + " model available: " + detail + "." + advice);
        }
        // The worker replaces the text of NOT_FOUND with its own «no model» template; the chain is
        // what an operator needs to read.
        log.warn("No {} model for agent {}: {}", purpose, agentId, detail);
        return new NotFoundStatusException("No " + purpose + " model available: " + detail);
    }

    /** Known to the registry and gone from the provider's last listing. A model it never saw is not «gone». */
    private static boolean isGone(LlmProviderModel row) {
        return row != null && row.getStatus() == LlmProviderModelStatus.UNAVAILABLE;
    }

    /**
     * The purpose's modality against the model's declared one. An empty list means «not declared»,
     * never «cannot»: a model configured before the first discovery stays usable.
     */
    private static boolean supportsPurpose(LlmProviderModel row, LlmPurpose purpose) {
        List<String> declared = declaredModality(row, purpose);
        return declared == null || declared.isEmpty() || declared.contains(IMAGE_MODALITY);
    }

    private static String modalityMiss(Candidate candidate, LlmPurpose purpose) {
        boolean generating = purpose == LlmPurpose.IMAGE;
        return "model '" + candidate.model() + "' of provider '" + candidate.provider().getName()
                + "' cannot " + (generating ? "generate" : "accept") + " images: it declares "
                + (generating ? "output" : "input") + " modalities "
                + declaredModality(candidate.row(), purpose);
    }

    private static List<String> declaredModality(LlmProviderModel row, LlmPurpose purpose) {
        if (row == null) {
            return null;
        }
        return switch (purpose) {
            case IMAGE -> row.getOutputModalities();
            case VISION -> row.getInputModalities();
            default -> null;
        };
    }

    /** Why this provider cannot serve the purpose — user-facing text, not a log line. */
    private static String miss(LlmProvider provider, LlmPurpose purpose) {
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

    // ---- finalisation ----------------------------------------------------------------------

    /**
     * Finalisation: key decryption plus the extra_body merge and the modalities of the picked row.
     */
    private ResolvedLlm toResolved(Candidate pick, LlmPurpose purpose, LlmSource source) {
        LlmProvider provider = pick.provider();
        String apiKey = llmProviderService.decryptApiKey(provider);
        LlmProviderModel row = pick.row();
        // The registry defaults belong to chat/completions only: an images or audio endpoint rejects them.
        Map<String, Object> defaults = purpose.isText() ? registryDefaults(row) : Map.of();
        Map<String, Object> extraBody = ExtraBodyMerge.merge(
                ExtraBodyMerge.merge(defaults, provider.getExtraBody()),
                row != null ? row.getExtraBody() : null);
        Map<String, Object> metadata = row == null || row.getRawMetadata() == null
                ? Map.of() : row.getRawMetadata();
        return new ResolvedLlm(provider, pick.model(), apiKey, extraBody,
                modalities(row == null ? null : row.getInputModalities()),
                modalities(row == null ? null : row.getOutputModalities()),
                metadata, source == LlmSource.PLATFORM);
    }

    /**
     * The request fields the registry can vouch for, laid under both extra_body levels. Without an
     * output limit the hoster applies its own default, and a reasoning model can spend a small one on
     * thinking alone and end with {@code length} before a word of the answer. Half of the context
     * window is left to the input: the prompt size is unknown here, and a hoster that checks
     * {@code prompt + max_tokens} against the window refuses the whole request. The key follows
     * {@code supported_parameters}, since OpenAI's reasoning models reject {@code max_tokens}; an
     * unknown list sends nothing rather than guess.
     */
    static Map<String, Object> registryDefaults(LlmProviderModel row) {
        if (row == null || row.getMaxOutputTokens() == null || row.getSupportedParameters() == null) {
            return Map.of();
        }
        int limit = Math.min(row.getMaxOutputTokens(), MAX_TOKENS_CAP);
        if (row.getContextWindow() != null) {
            limit = Math.min(limit, row.getContextWindow() / 2);
        }
        if (limit <= 0) {
            return Map.of();
        }
        List<String> supported = row.getSupportedParameters();
        if (supported.contains("max_tokens")) {
            return Map.of("max_tokens", limit);
        }
        if (supported.contains("max_completion_tokens")) {
            return Map.of("max_completion_tokens", limit);
        }
        return Map.of();
    }

    /** An absent registry row or an absent field are the same thing for the caller: nothing declared. */
    private static List<String> modalities(List<String> declared) {
        return declared == null ? List.of() : List.copyOf(declared);
    }

    private static <T> List<T> concat(List<T> first, List<T> second) {
        if (second.isEmpty()) {
            return first;
        }
        List<T> result = new ArrayList<>(first.size() + second.size());
        result.addAll(first);
        result.addAll(second);
        return result;
    }

    private static List<String> distinct(List<String> values) {
        return values.stream().distinct().toList();
    }
}
