package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import ru.agimate.controlapi.database.entities.LlmModelInfo;
import ru.agimate.controlapi.database.entities.LlmProvider;
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
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByName(agent.getId());
        if (bindings.isEmpty()) {
            return platformFallbackEntry();
        }
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId())))
                .toList();
    }

    public Map<UUID, List<AgentLlmResponse>> listForAgents(List<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdInOrderByAgentIdAscNameAsc(agentIds);
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);

        Map<UUID, List<AgentLlmResponse>> result = new HashMap<>();
        for (AgentLlm b : bindings) {
            result.computeIfAbsent(b.getAgentId(), k -> new java.util.ArrayList<>())
                    .add(AgentLlmResponse.from(b, providersById.get(b.getLlmProviderId())));
        }
        // Агенты без привязок работают через платформенный fallback — показываем эффективную модель.
        List<AgentLlmResponse> fallback = platformFallbackEntry();
        if (!fallback.isEmpty()) {
            agentIds.stream()
                    .filter(id -> !result.containsKey(id))
                    .forEach(id -> result.put(id, fallback));
        }
        return result;
    }

    private List<AgentLlmResponse> platformFallbackEntry() {
        return llmProviderService.findUsablePlatformProvider()
                .map(p -> List.of(AgentLlmResponse.platformFallback(p)))
                .orElse(List.of());
    }

    @Transactional
    public AgentLlmResponse create(UUID agentId, UUID userId, CreateAgentLlmRequest request) {
        Agent agent = requireOwnedAgent(agentId, userId);
        LlmProvider provider = llmProviderService.requireOwned(request.llmProviderId(), userId);
        validateModel(provider, request.model());

        if (agentLlmRepository.existsByAgentIdAndName(agent.getId(), request.name())) {
            throw new ConflictStatusException("Agent already has an LLM binding with name '" + request.name() + "'");
        }

        AgentLlm binding = AgentLlm.builder()
                .userId(userId)
                .agentId(agent.getId())
                .llmProviderId(provider.getId())
                .name(request.name())
                .model(request.model())
                .build();
        binding = agentLlmRepository.save(binding);

        log.info("Created agent_llm: agent={} name={} provider={} model={}",
                agent.getId(), binding.getName(), provider.getId(), binding.getModel());
        return AgentLlmResponse.from(binding, provider);
    }

    @Transactional
    public AgentLlmResponse replace(UUID agentId, UUID userId, String name, UpdateAgentLlmRequest request) {
        Agent agent = requireOwnedAgent(agentId, userId);
        AgentLlm binding = agentLlmRepository.findByAgentIdAndName(agent.getId(), name)
                .orElseThrow(() -> new NotFoundStatusException("LLM binding '" + name + "' not found for this agent"));
        LlmProvider provider = llmProviderService.requireOwned(request.llmProviderId(), userId);
        validateModel(provider, request.model());

        binding.setLlmProviderId(provider.getId());
        binding.setModel(request.model());
        binding = agentLlmRepository.save(binding);

        log.info("Replaced agent_llm: agent={} name={} provider={} model={}",
                agent.getId(), name, provider.getId(), binding.getModel());
        return AgentLlmResponse.from(binding, provider);
    }

    @Transactional
    public void delete(UUID agentId, UUID userId, String name) {
        Agent agent = requireOwnedAgent(agentId, userId);
        AgentLlm binding = agentLlmRepository.findByAgentIdAndName(agent.getId(), name)
                .orElseThrow(() -> new NotFoundStatusException("LLM binding '" + name + "' not found for this agent"));
        agentLlmRepository.delete(binding);
        log.info("Deleted agent_llm: agent={} name={}", agent.getId(), name);
    }

    public List<AgentLlmRuntimeResponse> runtimeForAgent(UUID agentId) {
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentIdOrderByName(agentId);
        Map<UUID, LlmProvider> providersById = loadProviders(bindings);
        return bindings.stream()
                .map(b -> toRuntime(b, providersById.get(b.getLlmProviderId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public AgentLlmRuntimeResponse runtimeForAgentByName(UUID agentId, String name) {
        AgentLlm binding = agentLlmRepository.findByAgentIdAndName(agentId, name)
                .orElseThrow(() -> new NotFoundStatusException("LLM binding '" + name + "' not found for this agent"));
        LlmProvider provider = llmProviderRepository.findById(binding.getLlmProviderId())
                .orElseThrow(() -> new NotFoundStatusException("Linked LLM provider not found"));
        AgentLlmRuntimeResponse runtime = toRuntime(binding, provider);
        if (runtime == null) {
            throw new NotFoundStatusException("LLM provider for binding '" + name + "' is disabled");
        }
        return runtime;
    }

    private AgentLlmRuntimeResponse toRuntime(AgentLlm binding, LlmProvider provider) {
        if (provider == null || !provider.isEnabled()) {
            return null;
        }
        String apiKey = llmProviderService.decryptApiKey(provider);
        return new AgentLlmRuntimeResponse(
                binding.getName(),
                provider.getProviderType(),
                provider.getBaseUrl(),
                binding.getModel(),
                apiKey
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
        List<LlmModelInfo> models = provider.getAvailableModels();
        if (models == null || models.isEmpty()) {
            log.warn("LLM provider {} has no availableModels list — skipping model validation for '{}'",
                    provider.getId(), model);
            return;
        }
        boolean matches = models.stream().anyMatch(m -> model.equals(m.id()));
        if (!matches) {
            List<String> ids = models.stream().map(LlmModelInfo::id).toList();
            throw new BadRequestStatusException(
                    "Model '" + model + "' is not in the provider's available models. "
                            + "Refresh models or use one of: " + ids);
        }
    }
}
