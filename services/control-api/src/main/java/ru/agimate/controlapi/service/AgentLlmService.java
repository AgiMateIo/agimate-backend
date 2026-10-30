package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.agent.dto.AgentLlmRuntimeResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.controlapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.controlapi.controller.manage.dto.llm.UpdateAgentLlmRequest;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The CRUD of {@code agent_llms}: a purpose holds an ordered chain of bindings, and the resolver takes
 * the first eligible one (docs/decisions/agent-llm-purpose-chain.md). The order is set by a human —
 * {@link #create} appends, {@link #replaceChain} rewrites the whole chain.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentLlmService {

    /**
     * Ceiling on a purpose's chain: resolution runs on every llm call and every candidate costs a
     * registry read and a quota query. Beyond five it is a profile, not a fallback path.
     */
    public static final int MAX_BINDINGS_PER_PURPOSE = 5;

    private final AgentLlmRepository agentLlmRepository;
    private final AgentRepository agentRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderService llmProviderService;

    /** One link of a chain, as the platform connector passes it (no {@code controller/**} dependency). */
    public record Binding(UUID llmProviderId, String model) {
    }

    /**
     * Purposes in the declaration order of {@link LlmPurpose}, then priority. The column is {@code TEXT},
     * so the database order would be alphabetical; the enum's order is the one a human reads.
     */
    private static final Comparator<AgentLlm> BY_PURPOSE_THEN_PRIORITY =
            Comparator.comparing(AgentLlm::getPurpose).thenComparingInt(AgentLlm::getPriority);

    public List<AgentLlmResponse> listForAgent(UUID agentId, UUID userId) {
        Agent agent = requireOwnedAgent(agentId, userId);
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(agent.getId())
                .stream().sorted(BY_PURPOSE_THEN_PRIORITY).toList();
        if (bindings.isEmpty()) {
            return runsOnPlatformModels(agent) ? platformFallbackEntry() : List.of();
        }
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId())))
                .toList();
    }

    public Map<UUID, List<AgentLlmResponse>> listForAgents(List<Agent> agents) {
        if (agents.isEmpty()) {
            return Map.of();
        }
        List<UUID> agentIds = agents.stream().map(Agent::getId).toList();
        List<AgentLlm> bindings =
                agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAscPriorityAsc(agentIds);
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);

        Map<UUID, List<AgentLlmResponse>> result = new HashMap<>();
        for (AgentLlm b : bindings) {
            result.computeIfAbsent(b.getAgentId(), k -> new ArrayList<>())
                    .add(AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId())));
        }
        result.values().forEach(list -> list.sort(Comparator
                .comparing(AgentLlmResponse::purpose)
                .thenComparingInt(AgentLlmResponse::priority)));
        // Agents with no bindings run through the platform fallback — we show the effective model.
        List<AgentLlmResponse> fallback = platformFallbackEntry();
        if (!fallback.isEmpty()) {
            agents.stream()
                    .filter(AgentLlmService::runsOnPlatformModels)
                    .map(Agent::getId)
                    .filter(id -> !result.containsKey(id))
                    .forEach(id -> result.put(id, fallback));
        }
        return result;
    }

    /**
     * Whether the platform's own model is what this agent's loop would run on. Only ours is: an
     * external brain comes with its own model and never asks us for credentials, so «runs on the
     * platform model» would be a plain lie in its card. Explicit bindings are still shown for every
     * type — they drive the tool-side purposes (media), which the platform executes itself.
     */
    private static boolean runsOnPlatformModels(Agent agent) {
        return agent.getType() == AgentType.GENERIC;
    }

    /** Empty when the platform has no CHAT models declared — there is nothing to fall back to, so we show nothing. */
    private List<AgentLlmResponse> platformFallbackEntry() {
        return llmProviderService.findUsablePlatformProvider()
                .filter(p -> p.modelsFor(LlmPurpose.CHAT).map(m -> !m.isEmpty()).orElse(false))
                .map(p -> List.of(AgentLlmResponse.platformFallback(p)))
                .orElse(List.of());
    }

    @Transactional
    public AgentLlmResponse create(UUID agentId, UUID userId, CreateAgentLlmRequest request) {
        LlmPurpose purpose = request.purpose() != null ? request.purpose() : LlmPurpose.CHAT;
        AgentLlm binding = append(agentId, userId, request.llmProviderId(), request.model(), purpose);
        return AgentLlmResponse.from(binding, llmProviderService.requireOwned(request.llmProviderId(), userId));
    }

    /** The primitive overload — the platform connector's entry point (no {@code controller/**} dependency). */
    @Transactional
    public AgentLlm create(UUID agentId, UUID userId, UUID llmProviderId, String model, LlmPurpose purpose) {
        return append(agentId, userId, llmProviderId, model,
                purpose != null ? purpose : LlmPurpose.CHAT);
    }

    private AgentLlm append(UUID agentId, UUID userId, UUID llmProviderId, String model, LlmPurpose purpose) {
        Agent agent = requireOwnedAgent(agentId, userId);
        LlmProvider provider = llmProviderService.requireOwned(llmProviderId, userId);
        validateModel(provider, model);

        List<AgentLlm> chain =
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agent.getId(), purpose);
        if (chain.size() >= MAX_BINDINGS_PER_PURPOSE) {
            throw new ConflictStatusException("The " + purpose + " chain of this agent is full ("
                    + MAX_BINDINGS_PER_PURPOSE + " bindings); remove one before adding another");
        }
        if (chain.stream().anyMatch(b -> sameBinding(b, provider.getId(), model))) {
            throw new ConflictStatusException("The " + purpose + " chain already binds model '" + model
                    + "' of provider '" + provider.getName() + "'");
        }

        int priority = chain.stream().mapToInt(AgentLlm::getPriority).max().orElse(-1) + 1;
        AgentLlm binding = agentLlmRepository.save(AgentLlm.builder()
                .userId(userId)
                .agentId(agent.getId())
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(purpose)
                .priority(priority)
                .build());
        try {
            // Flush now: two POSTs compute the same max + 1, and the loser must get a conflict, not
            // a persistence error thrown from the commit after the method has returned.
            agentLlmRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("LLM bindings changed concurrently — retry");
        }

        log.info("Appended agent_llm: agent={} purpose={} provider={} model={} priority={}",
                agent.getId(), purpose, provider.getId(), binding.getModel(), priority);
        return binding;
    }

    @Transactional
    public List<AgentLlmResponse> replaceChain(UUID agentId, UUID userId, LlmPurpose purpose,
                                               UpdateAgentLlmRequest request) {
        List<Binding> bindings = request.bindings().stream()
                .map(b -> new Binding(b.llmProviderId(), b.model()))
                .toList();
        List<AgentLlm> chain = replaceChain(agentId, userId, purpose, bindings);
        Map<UUID, LlmProvider> providersById = loadProviders(chain);
        return chain.stream()
                .map(b -> AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId())))
                .toList();
    }

    /**
     * Rewrites the purpose's chain: the old rows are deleted and the list is inserted with
     * {@code priority} taken from the position. A delete-then-insert in one transaction, because the
     * unique {@code (agent_id, purpose, priority)} does not survive an in-place swap.
     *
     * @param bindings the chain in the wanted order; empty removes the purpose
     */
    @Transactional
    public List<AgentLlm> replaceChain(UUID agentId, UUID userId, LlmPurpose purpose,
                                       List<Binding> bindings) {
        Agent agent = requireOwnedAgent(agentId, userId);
        if (bindings.size() > MAX_BINDINGS_PER_PURPOSE) {
            throw new ConflictStatusException("A purpose chain holds at most "
                    + MAX_BINDINGS_PER_PURPOSE + " bindings, got " + bindings.size());
        }

        Set<String> seen = new HashSet<>();
        List<AgentLlm> rows = new ArrayList<>(bindings.size());
        int priority = 0;
        for (Binding binding : bindings) {
            LlmProvider provider = llmProviderService.requireOwned(binding.llmProviderId(), userId);
            String model = binding.model();
            if (model == null || model.isBlank()) {
                throw new BadRequestStatusException("Model is required for every binding");
            }
            validateModel(provider, model);
            if (!seen.add(provider.getId() + "\u0000" + model)) {
                throw new BadRequestStatusException("Duplicate binding of model '" + model
                        + "' of provider '" + provider.getName() + "' in one " + purpose + " chain");
            }
            rows.add(AgentLlm.builder()
                    .userId(userId)
                    .agentId(agent.getId())
                    .llmProviderId(provider.getId())
                    .model(model)
                    .purpose(purpose)
                    .priority(priority++)
                    .build());
        }

        agentLlmRepository.deleteAllInBatch(
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agent.getId(), purpose));
        List<AgentLlm> saved = agentLlmRepository.saveAll(rows);

        log.info("Replaced agent_llm chain: agent={} purpose={} bindings={}",
                agent.getId(), purpose, saved.size());
        return saved;
    }

    @Transactional
    public void delete(UUID agentId, UUID userId, LlmPurpose purpose) {
        Agent agent = requireOwnedAgent(agentId, userId);
        List<AgentLlm> chain =
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agent.getId(), purpose);
        if (chain.isEmpty()) {
            throw new NotFoundStatusException("No LLM binding for purpose " + purpose + " on this agent");
        }
        agentLlmRepository.deleteAllInBatch(chain);
        log.info("Deleted agent_llm chain: agent={} purpose={} bindings={}",
                agent.getId(), purpose, chain.size());
    }

    public List<AgentLlmRuntimeResponse> runtimeForAgent(UUID agentId) {
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByPurposeAscPriorityAsc(agentId)
                .stream().sorted(BY_PURPOSE_THEN_PRIORITY).toList();
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> toRuntime(b, providersById.get(b.getLlmProviderId())))
                .filter(Objects::nonNull)
                .toList();
    }

    /** The first binding of the purpose whose provider is enabled — the same order the resolver walks. */
    public AgentLlmRuntimeResponse runtimeForAgentByPurpose(UUID agentId, LlmPurpose purpose) {
        List<AgentLlm> chain =
                agentLlmRepository.findAllByAgentIdAndPurposeOrderByPriorityAsc(agentId, purpose);
        if (chain.isEmpty()) {
            throw new NotFoundStatusException("No LLM binding for purpose " + purpose + " on this agent");
        }
        Map<UUID, LlmProvider> providersById = loadProviders(chain);
        return chain.stream()
                .map(b -> toRuntime(b, providersById.get(b.getLlmProviderId())))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> new NotFoundStatusException(
                        "LLM provider for purpose " + purpose + " is disabled"));
    }

    private static boolean sameBinding(AgentLlm binding, UUID providerId, String model) {
        return binding.getLlmProviderId().equals(providerId) && binding.getModel().equals(model);
    }

    private AgentLlmRuntimeResponse toRuntime(AgentLlm binding, LlmProvider provider) {
        if (provider == null || !provider.isEnabled()) {
            return null;
        }
        return new AgentLlmRuntimeResponse(
                binding.getPurpose(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                binding.getModel(),
                binding.getPriority()
        );
    }

    private Map<UUID, LlmProvider> loadProviders(List<AgentLlm> bindings) {
        if (bindings.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = bindings.stream().map(AgentLlm::getLlmProviderId).distinct().toList();
        return llmProviderRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(LlmProvider::getId, Function.identity()));
    }

    private Agent requireOwnedAgent(UUID agentId, UUID userId) {
        Agent agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return agent;
    }

    private void validateModel(LlmProvider provider, String model) {
        llmProviderService.validateModelsKnown(provider, List.of(model));
    }
}
