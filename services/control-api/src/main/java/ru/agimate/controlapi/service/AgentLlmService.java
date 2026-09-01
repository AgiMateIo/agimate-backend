package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentLlmService {

    private final AgentLlmRepository agentLlmRepository;
    private final AgentRepository agentRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderService llmProviderService;

    public List<AgentLlmResponse> listForAgent(UUID agentId, UUID userId) {
        Agent agent = requireOwnedAgent(agentId, userId);
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByPurpose(agent.getId());
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
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscPurposeAsc(agentIds);
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);

        Map<UUID, List<AgentLlmResponse>> result = new HashMap<>();
        for (AgentLlm b : bindings) {
            result.computeIfAbsent(b.getAgentId(), k -> new java.util.ArrayList<>())
                    .add(AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId())));
        }
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
        AgentLlm binding = create(agentId, userId, request.llmProviderId(), request.model(), request.purpose());
        return AgentLlmResponse.from(binding, llmProviderService.requireOwned(request.llmProviderId(), userId));
    }

    /** The primitive overload — the platform connector's entry point (no {@code controller/**} dependency). */
    @Transactional
    public AgentLlm create(UUID agentId, UUID userId, UUID llmProviderId, String model, LlmPurpose purpose) {
        Agent agent = requireOwnedAgent(agentId, userId);
        LlmProvider provider = llmProviderService.requireOwned(llmProviderId, userId);
        validateModel(provider, model);

        LlmPurpose resolvedPurpose = purpose != null ? purpose : LlmPurpose.CHAT;
        if (agentLlmRepository.existsByAgentIdAndPurpose(agent.getId(), resolvedPurpose)) {
            throw new ConflictStatusException(
                    "Agent already has an LLM binding for purpose " + resolvedPurpose + "; replace it with PUT");
        }

        AgentLlm binding = AgentLlm.builder()
                .userId(userId)
                .agentId(agent.getId())
                .llmProviderId(provider.getId())
                .model(model)
                .purpose(resolvedPurpose)
                .build();
        binding = agentLlmRepository.save(binding);

        log.info("Created agent_llm: agent={} purpose={} provider={} model={}",
                agent.getId(), binding.getPurpose(), provider.getId(), binding.getModel());
        return binding;
    }

    @Transactional
    public AgentLlmResponse replace(UUID agentId, UUID userId, LlmPurpose purpose, UpdateAgentLlmRequest request) {
        AgentLlm binding = replace(agentId, userId, purpose, request.llmProviderId(), request.model());
        return AgentLlmResponse.from(binding, llmProviderService.requireOwned(request.llmProviderId(), userId));
    }

    /** The primitive overload — the platform connector's entry point (no {@code controller/**} dependency). */
    @Transactional
    public AgentLlm replace(UUID agentId, UUID userId, LlmPurpose purpose,
                            UUID llmProviderId, String model) {
        Agent agent = requireOwnedAgent(agentId, userId);
        AgentLlm binding = requireBinding(agent.getId(), purpose);
        LlmProvider provider = llmProviderService.requireOwned(llmProviderId, userId);
        validateModel(provider, model);

        binding.setLlmProviderId(provider.getId());
        binding.setModel(model);
        binding = agentLlmRepository.save(binding);

        log.info("Replaced agent_llm: agent={} purpose={} provider={} model={}",
                agent.getId(), purpose, provider.getId(), binding.getModel());
        return binding;
    }

    @Transactional
    public void delete(UUID agentId, UUID userId, LlmPurpose purpose) {
        Agent agent = requireOwnedAgent(agentId, userId);
        agentLlmRepository.delete(requireBinding(agent.getId(), purpose));
        log.info("Deleted agent_llm: agent={} purpose={}", agent.getId(), purpose);
    }

    public List<AgentLlmRuntimeResponse> runtimeForAgent(UUID agentId) {
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByPurpose(agentId);
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> toRuntime(b, providersById.get(b.getLlmProviderId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public AgentLlmRuntimeResponse runtimeForAgentByPurpose(UUID agentId, LlmPurpose purpose) {
        AgentLlm binding = requireBinding(agentId, purpose);
        LlmProvider provider = llmProviderRepository.findById(binding.getLlmProviderId())
                .orElseThrow(() -> new NotFoundStatusException("Linked LLM provider not found"));
        AgentLlmRuntimeResponse runtime = toRuntime(binding, provider);
        if (runtime == null) {
            throw new NotFoundStatusException("LLM provider for purpose " + purpose + " is disabled");
        }
        return runtime;
    }

    private AgentLlm requireBinding(UUID agentId, LlmPurpose purpose) {
        return agentLlmRepository.findByAgentIdAndPurpose(agentId, purpose)
                .orElseThrow(() -> new NotFoundStatusException(
                        "No LLM binding for purpose " + purpose + " on this agent"));
    }

    private AgentLlmRuntimeResponse toRuntime(AgentLlm binding, LlmProvider provider) {
        if (provider == null || !provider.isEnabled()) {
            return null;
        }
        return new AgentLlmRuntimeResponse(
                binding.getPurpose(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                binding.getModel()
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
