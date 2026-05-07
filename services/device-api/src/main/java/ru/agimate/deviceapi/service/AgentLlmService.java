package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.agent.dto.AgentLlmRuntimeResponse;
import ru.agimate.deviceapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.deviceapi.controller.manage.dto.llm.CreateAgentLlmRequest;
import ru.agimate.deviceapi.controller.manage.dto.llm.UpdateAgentLlmRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentLlm;
import ru.agimate.deviceapi.database.entities.LlmProvider;
import ru.agimate.deviceapi.database.repositories.AgentLlmRepository;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.LlmProviderRepository;

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

    public List<AgentLlmResponse> listForAgent(UUID agentPubId, UUID userPubId) {
        Agent agent = requireOwnedAgent(agentPubId, userPubId);
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentPubIdOrderByName(agent.getPubId());
        Map<UUID, LlmProvider> providersByPubId = loadProviders(bindings);
        return bindings.stream()
                .map(b -> AgentLlmResponse.from(b, providersByPubId.get(b.getLlmProviderPubId())))
                .toList();
    }

    public Map<UUID, List<AgentLlmResponse>> listForAgents(List<UUID> agentPubIds) {
        if (agentPubIds.isEmpty()) {
            return Map.of();
        }
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentPubIdInOrderByAgentPubIdAscNameAsc(agentPubIds);
        Map<UUID, LlmProvider> providersByPubId = loadProviders(bindings);

        Map<UUID, List<AgentLlmResponse>> result = new HashMap<>();
        for (AgentLlm b : bindings) {
            result.computeIfAbsent(b.getAgentPubId(), k -> new java.util.ArrayList<>())
                    .add(AgentLlmResponse.from(b, providersByPubId.get(b.getLlmProviderPubId())));
        }
        return result;
    }

    @Transactional
    public AgentLlmResponse create(UUID agentPubId, UUID userPubId, CreateAgentLlmRequest request) {
        Agent agent = requireOwnedAgent(agentPubId, userPubId);
        LlmProvider provider = llmProviderService.requireOwned(request.llmProviderPubId(), userPubId);
        validateModel(provider, request.model());

        if (agentLlmRepository.existsByAgentPubIdAndName(agent.getPubId(), request.name())) {
            throw new ConflictStatusException("Agent already has an LLM binding with name '" + request.name() + "'");
        }

        AgentLlm binding = AgentLlm.builder()
                .userPubId(userPubId)
                .agentPubId(agent.getPubId())
                .llmProviderPubId(provider.getPubId())
                .name(request.name())
                .model(request.model())
                .build();
        binding = agentLlmRepository.save(binding);

        log.info("Created agent_llm: agent={} name={} provider={} model={}",
                agent.getPubId(), binding.getName(), provider.getPubId(), binding.getModel());
        return AgentLlmResponse.from(binding, provider);
    }

    @Transactional
    public AgentLlmResponse replace(UUID agentPubId, UUID userPubId, String name, UpdateAgentLlmRequest request) {
        Agent agent = requireOwnedAgent(agentPubId, userPubId);
        AgentLlm binding = agentLlmRepository.findByAgentPubIdAndName(agent.getPubId(), name)
                .orElseThrow(() -> new NotFoundStatusException("LLM binding '" + name + "' not found for this agent"));
        LlmProvider provider = llmProviderService.requireOwned(request.llmProviderPubId(), userPubId);
        validateModel(provider, request.model());

        binding.setLlmProviderPubId(provider.getPubId());
        binding.setModel(request.model());
        binding = agentLlmRepository.save(binding);

        log.info("Replaced agent_llm: agent={} name={} provider={} model={}",
                agent.getPubId(), name, provider.getPubId(), binding.getModel());
        return AgentLlmResponse.from(binding, provider);
    }

    @Transactional
    public void delete(UUID agentPubId, UUID userPubId, String name) {
        Agent agent = requireOwnedAgent(agentPubId, userPubId);
        AgentLlm binding = agentLlmRepository.findByAgentPubIdAndName(agent.getPubId(), name)
                .orElseThrow(() -> new NotFoundStatusException("LLM binding '" + name + "' not found for this agent"));
        agentLlmRepository.delete(binding);
        log.info("Deleted agent_llm: agent={} name={}", agent.getPubId(), name);
    }

    public List<AgentLlmRuntimeResponse> runtimeForAgent(UUID agentPubId) {
        List<AgentLlm> bindings = agentLlmRepository.findAllByAgentPubIdOrderByName(agentPubId);
        Map<UUID, LlmProvider> providersByPubId = loadProviders(bindings);
        return bindings.stream()
                .map(b -> toRuntime(b, providersByPubId.get(b.getLlmProviderPubId())))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public AgentLlmRuntimeResponse runtimeForAgentByName(UUID agentPubId, String name) {
        AgentLlm binding = agentLlmRepository.findByAgentPubIdAndName(agentPubId, name)
                .orElseThrow(() -> new NotFoundStatusException("LLM binding '" + name + "' not found for this agent"));
        LlmProvider provider = llmProviderRepository.findByPubId(binding.getLlmProviderPubId())
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
        List<UUID> pubIds = bindings.stream().map(AgentLlm::getLlmProviderPubId).distinct().toList();
        return llmProviderRepository.findAllByPubIdIn(pubIds).stream()
                .collect(Collectors.toMap(LlmProvider::getPubId, Function.identity()));
    }

    private Agent requireOwnedAgent(UUID agentPubId, UUID userPubId) {
        Agent agent = agentRepository.findByPubId(agentPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return agent;
    }

    private void validateModel(LlmProvider provider, String model) {
        List<String> models = provider.getAvailableModels();
        if (models == null || models.isEmpty()) {
            log.warn("LLM provider {} has no availableModels list — skipping model validation for '{}'",
                    provider.getPubId(), model);
            return;
        }
        if (!models.contains(model)) {
            throw new BadRequestStatusException(
                    "Model '" + model + "' is not in the provider's available models. "
                            + "Refresh models or use one of: " + models);
        }
    }
}
