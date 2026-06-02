package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.controller.agent.dto.AgentConfigResponse;
import ru.agimate.deviceapi.controller.agent.dto.AgentContextResponse;
import ru.agimate.deviceapi.controller.agent.dto.ToolDefinition;
import ru.agimate.deviceapi.service.dto.AgentToolSpec;
import ru.agimate.deviceapi.controller.manage.dto.AgentResponse;
import ru.agimate.deviceapi.controller.manage.dto.AgentSkillSummary;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.deviceapi.controller.manage.dto.llm.AgentLlmResponse;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.enums.AgentType;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentSkillRepository;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AgenticTeamRepository;
import ru.agimate.deviceapi.database.repositories.AppRepository;
import ru.agimate.deviceapi.util.AppKeyUtils;
import ru.agimate.deviceapi.util.GeneratedAppKey;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentService {

    public static final String AGENT_KEY_PREFIX = "agnt";

    private final AgentRepository agentRepository;
    private final AgentToolPolicyRepository agentToolPolicyRepository;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AppRepository appRepository;
    private final AgentLlmService agentLlmService;

    public Page<AgentResponse> getAllForUser(UUID userPubId, UUID agenticTeamId, String search, int page, int size) {
        if (agenticTeamId != null) {
            agenticTeamRepository.findById(agenticTeamId)
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
        }
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        Page<Agent> agents = agentRepository.searchForUser(
                userPubId, agenticTeamId, normalizedSearch, PageRequest.of(page, size));

        List<UUID> teamIds = agents.getContent().stream()
                .map(Agent::getAgenticTeamId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<UUID, AgenticTeam> teamsById = agenticTeamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(AgenticTeam::getId, Function.identity()));

        List<UUID> agentIds = agents.getContent().stream().map(Agent::getId).toList();
        Map<UUID, List<AgentSkillSummary>> skillsByAgent = loadSkillSummaries(agentIds);
        Map<UUID, List<AgentLlmResponse>> llmsByAgent = agentLlmService.listForAgents(agentIds);

        return agents.map(agent -> {
            var team = agent.getAgenticTeamId() != null ? teamsById.get(agent.getAgenticTeamId()) : null;
            var skills = skillsByAgent.getOrDefault(agent.getId(), List.of());
            var llms = llmsByAgent.getOrDefault(agent.getId(), List.of());
            return AgentResponse.from(agent, team, skills, llms);
        });
    }

    private Map<UUID, List<AgentSkillSummary>> loadSkillSummaries(Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<AgentSkillSummary>> result = new HashMap<>();
        for (Object[] row : agentSkillRepository.findSkillSummariesByAgentIdIn(agentIds)) {
            UUID agentId = (UUID) row[0];
            UUID skillId = (UUID) row[1];
            String skillName = (String) row[2];
            result.computeIfAbsent(agentId, k -> new ArrayList<>())
                    .add(new AgentSkillSummary(skillId, skillName));
        }
        return result;
    }

    public Agent findById(UUID id) {
        return agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    public AgentResponse getById(UUID id) {
        Agent agent = findById(id);
        var team = resolveTeam(agent.getAgenticTeamId());
        var skills = loadSkillSummaries(List.of(id)).getOrDefault(id, List.of());
        var llms = agentLlmService.listForAgents(List.of(id)).getOrDefault(id, List.of());
        return AgentResponse.from(agent, team, skills, llms);
    }

    public AgentConfigResponse getConfigById(UUID agentId) {
        Agent agent = findById(agentId);

        var toolPolicies = agentToolPolicyRepository.findByAgentId(agentId);
        var triggerPolicies = agentTriggerPolicyRepository.findByAgentId(agentId);

        Set<String> allowedToolNames = toolPolicies.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .map(AgentToolPolicy::getToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, ToolDefinition> toolDefinitionMap = buildToolDefinitionMap(agent.getUserPubId());

        List<ToolDefinition> toolDefinitions = allowedToolNames.stream()
                .map(name -> toolDefinitionMap.getOrDefault(name,
                        new ToolDefinition(name, null, null)))
                .toList();

        List<String> triggerNames = triggerPolicies.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .map(AgentTriggerPolicy::getTriggerName)
                .filter(Objects::nonNull)
                .toList();

        return new AgentConfigResponse(
                agent.getId(),
                agent.getPrompt(),
                toolDefinitions,
                triggerNames
        );
    }

    public AgentContextResponse getContextById(UUID agentId) {
        Agent agent = findById(agentId);

        AgentContextResponse.Self self = new AgentContextResponse.Self(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getPrompt()
        );

        AgentContextResponse.Team team = null;
        List<AgentContextResponse.TeamAgent> teamAgents = List.of();

        if (agent.getAgenticTeamId() != null) {
            AgenticTeam teamEntity = agenticTeamRepository.findById(agent.getAgenticTeamId()).orElse(null);
            if (teamEntity != null) {
                team = new AgentContextResponse.Team(
                        teamEntity.getId(),
                        teamEntity.getName(),
                        teamEntity.getDescription()
                );
                teamAgents = agentRepository
                        .findByUserPubIdAndAgenticTeamId(agent.getUserPubId(), teamEntity.getId())
                        .stream()
                        .map(a -> new AgentContextResponse.TeamAgent(
                                a.getId(),
                                a.getName(),
                                a.getDescription()
                        ))
                        .toList();
            }
        }

        return new AgentContextResponse(self, team, teamAgents);
    }

    public List<ToolDefinition> getAvailableTools(UUID agentId) {
        Agent agent = findById(agentId);

        var toolPolicies = agentToolPolicyRepository.findByAgentId(agentId);

        Set<String> allowedToolNames = toolPolicies.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .map(AgentToolPolicy::getToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, ToolDefinition> toolDefinitionMap = buildToolDefinitionMap(agent.getUserPubId());

        return allowedToolNames.stream()
                .map(name -> toolDefinitionMap.getOrDefault(name,
                        new ToolDefinition(name, null, null)))
                .toList();
    }

    /**
     * Same selection as {@link #getAvailableTools} but exposes the raw JSON Schema
     * stored under {@code App.tools[name].parameters} (convention key).
     */
    public List<AgentToolSpec> getAvailableToolSpecs(UUID agentId) {
        Agent agent = findById(agentId);

        var toolPolicies = agentToolPolicyRepository.findByAgentId(agentId);

        Set<String> allowedToolNames = toolPolicies.stream()
                .filter(p -> p.getEffect() == AccessEffect.ALLOW)
                .map(AgentToolPolicy::getToolName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<String, AgentToolSpec> specMap = buildToolSpecMap(agent.getUserPubId());

        return allowedToolNames.stream()
                .map(name -> specMap.getOrDefault(name, new AgentToolSpec(name, null, null)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, AgentToolSpec> buildToolSpecMap(UUID userPubId) {
        List<App> apps = appRepository.findByUserPubIdNotDeleted(userPubId);
        Map<String, AgentToolSpec> map = new LinkedHashMap<>();

        for (App app : apps) {
            if (app.getTools() == null) continue;
            for (var entry : app.getTools().entrySet()) {
                String toolName = entry.getKey();
                var value = (Map<String, Object>) entry.getValue();
                String description = value.getOrDefault("description", "").toString();
                Object parameters = value.get("parameters");
                map.put(toolName, new AgentToolSpec(toolName, description, parameters));
            }
        }

        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<String, ToolDefinition> buildToolDefinitionMap(UUID userPubId) {
        List<App> apps = appRepository.findByUserPubIdNotDeleted(userPubId);
        Map<String, ToolDefinition> map = new LinkedHashMap<>();

        for (App app : apps) {
            if (app.getTools() == null) continue;
            for (var entry : app.getTools().entrySet()) {
                String toolName = entry.getKey();
                var value = (Map<String, Object>) entry.getValue();
                String description = value.getOrDefault("description", "").toString();
                List<String> params = value.get("params") instanceof List<?> list
                        ? list.stream().map(Object::toString).toList()
                        : List.of();
                map.put(toolName, new ToolDefinition(toolName, description, params));
            }
        }

        return map;
    }

    @Transactional
    public AgentCreateResult create(UUID userPubId, CreateAgentRequest request) {
        AgentType type = request.type() != null
                ? request.type() : AgentType.CENTRIFUGO;
        validateWebhookFields(type, request.webhookUrl());

        AgenticTeam team = null;
        if (request.agenticTeamId() != null) {
            team = agenticTeamRepository.findById(request.agenticTeamId())
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            if (!team.getUserPubId().equals(userPubId)) {
                throw new ForbiddenStatusException("Access denied to the specified team");
            }
        }

        GeneratedAppKey generatedKey = AppKeyUtils.generate(AGENT_KEY_PREFIX);

        Agent agent = Agent.builder()
                .keyHash(generatedKey.secretHash())
                .keyId(generatedKey.keyId())
                .userPubId(userPubId)
                .name(request.name())
                .description(request.description())
                .prompt(request.prompt())
                .type(type)
                .webhookUrl(request.webhookUrl())
                .webhookAuthHeader(request.webhookAuthHeader())
                .agenticTeamId(team != null ? team.getId() : null)
                .build();
        agent = agentRepository.save(agent);

        log.info("Created agent id={}, user={}", agent.getId(), userPubId);
        return new AgentCreateResult(agent, team, generatedKey.fullKey());
    }

    @Transactional
    public AgentCreateResult regenerateKey(UUID id, UUID userPubId) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        GeneratedAppKey generatedKey = AppKeyUtils.generate(AGENT_KEY_PREFIX);
        agent.setKeyHash(generatedKey.secretHash());
        agent.setKeyId(generatedKey.keyId());
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());

        log.info("Regenerated key for agent id={}", id);
        return new AgentCreateResult(agent, team, generatedKey.fullKey());
    }

    @Transactional
    public AgentResponse update(UUID id, UUID userPubId, UpdateAgentRequest request) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        AgentType type = request.type() != null
                ? request.type() : agent.getType();
        validateWebhookFields(type, request.webhookUrl());

        if (request.name() != null) {
            agent.setName(request.name());
        }
        agent.setDescription(request.description());
        agent.setPrompt(request.prompt());
        agent.setType(type);
        agent.setWebhookUrl(request.webhookUrl());
        agent.setWebhookAuthHeader(request.webhookAuthHeader());
        if (request.enabled() != null) {
            agent.setEnabled(request.enabled());
        }
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());
        var skills = loadSkillSummaries(List.of(id)).getOrDefault(id, List.of());
        var llms = agentLlmService.listForAgents(List.of(id)).getOrDefault(id, List.of());

        log.info("Updated agent id={}", id);
        return AgentResponse.from(agent, team, skills, llms);
    }

    @Transactional
    public void delete(UUID id, UUID userPubId) {
        Agent agent = agentRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        agentToolPolicyRepository.deleteByAgentId(agent.getId());
        agentTriggerPolicyRepository.deleteByAgentId(agent.getId());
        agentRepository.delete(agent);

        log.info("Deleted agent id={}", id);
    }

    private AgenticTeam resolveTeam(UUID agenticTeamId) {
        if (agenticTeamId == null) {
            return null;
        }
        return agenticTeamRepository.findById(agenticTeamId).orElse(null);
    }

    private void validateWebhookFields(AgentType type, String webhookUrl) {
        if (type == AgentType.WEBHOOK && (webhookUrl == null || webhookUrl.isBlank())) {
            throw new ValidationErrorStatusException("webhookUrl", "Webhook url is required when type is WEBHOOK");
        }
    }

    public record AgentCreateResult(Agent agent, AgenticTeam team, String plaintextKey) {}
}
