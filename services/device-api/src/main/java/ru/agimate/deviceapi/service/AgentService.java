package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.abac.AccessEffect;
import ru.agimate.deviceapi.controller.agent.dto.AgentConfigResponse;
import ru.agimate.deviceapi.controller.agent.dto.ToolDefinition;
import ru.agimate.deviceapi.controller.manage.dto.AgentResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentToolPolicy;
import ru.agimate.deviceapi.database.entities.AgentTriggerPolicy;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.entities.App;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentToolPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerPolicyRepository;
import ru.agimate.deviceapi.database.repositories.AgenticTeamRepository;
import ru.agimate.deviceapi.database.repositories.AppRepository;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentService {

    private final AgentRepository agentRepository;
    private final AgentToolPolicyRepository agentToolPolicyRepository;
    private final AgentTriggerPolicyRepository agentTriggerPolicyRepository;
    private final AgenticTeamRepository agenticTeamRepository;
    private final AppRepository appRepository;

    public List<AgentResponse> getAllForUser(UUID userPubId, UUID agenticTeamPubId) {
        List<Agent> agents;
        if (agenticTeamPubId != null) {
            AgenticTeam team = agenticTeamRepository.findByPubId(agenticTeamPubId)
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            agents = agentRepository.findByUserPubIdAndAgenticTeamId(userPubId, team.getId());
        } else {
            agents = agentRepository.findByUserPubId(userPubId);
        }

        List<Long> teamIds = agents.stream()
                .map(Agent::getAgenticTeamId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, AgenticTeam> teamsById = agenticTeamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(AgenticTeam::getId, Function.identity()));

        return agents.stream()
                .map(agent -> {
                    var team = agent.getAgenticTeamId() != null ? teamsById.get(agent.getAgenticTeamId()) : null;
                    return AgentResponse.from(agent, team);
                })
                .toList();
    }

    public Agent findByApiKeyPubId(UUID apiKeyPubId) {
        return agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    public AgentResponse getByApiKeyPubId(UUID apiKeyPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        var team = resolveTeam(agent.getAgenticTeamId());
        return AgentResponse.from(agent, team);
    }

    public AgentConfigResponse getConfigByApiKeyPubId(UUID apiKeyPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        var toolPolicies = agentToolPolicyRepository.findByApiKeyPubId(apiKeyPubId);
        var triggerPolicies = agentTriggerPolicyRepository.findByApiKeyPubId(apiKeyPubId);

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
                agent.getApiKeyPubId(),
                agent.getPrompt(),
                toolDefinitions,
                triggerNames
        );
    }

    public List<ToolDefinition> getAvailableTools(UUID apiKeyPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        var toolPolicies = agentToolPolicyRepository.findByApiKeyPubId(apiKeyPubId);

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
    public AgentResponse create(UUID userPubId, CreateAgentRequest request) {
        validateWebhookFields(request.triggersTo(), request.webhookUrl());

        AgenticTeam team = null;
        if (request.agenticTeamPubId() != null) {
            team = agenticTeamRepository.findByPubId(request.agenticTeamPubId())
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            if (!team.getUserPubId().equals(userPubId)) {
                throw new ForbiddenStatusException("Access denied to the specified team");
            }
        }

        Agent agent = Agent.builder()
                .apiKeyPubId(request.apiKeyPubId())
                .userPubId(userPubId)
                .name(request.name())
                .prompt(request.prompt())
                .triggersAllowAll(request.triggersAllowAll())
                .triggersTo(request.triggersTo())
                .webhookUrl(request.webhookUrl())
                .webhookAuthHeader(request.webhookAuthHeader())
                .agenticTeamId(team != null ? team.getId() : null)
                .build();
        agent = agentRepository.save(agent);

        log.info("Created agent for apiKeyPubId={}, user={}", request.apiKeyPubId(), userPubId);
        return AgentResponse.from(agent, team);
    }

    @Transactional
    public AgentResponse update(UUID apiKeyPubId, UUID userPubId, UpdateAgentRequest request) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        validateWebhookFields(request.triggersTo(), request.webhookUrl());

        if (request.name() != null) {
            agent.setName(request.name());
        }
        agent.setPrompt(request.prompt());
        agent.setTriggersAllowAll(request.triggersAllowAll());
        agent.setTriggersTo(request.triggersTo());
        agent.setWebhookUrl(request.webhookUrl());
        agent.setWebhookAuthHeader(request.webhookAuthHeader());
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());

        log.info("Updated agent for apiKeyPubId={}", apiKeyPubId);
        return AgentResponse.from(agent, team);
    }

    @Transactional
    public void delete(UUID apiKeyPubId, UUID userPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        agentToolPolicyRepository.deleteByApiKeyPubId(apiKeyPubId);
        agentTriggerPolicyRepository.deleteByApiKeyPubId(apiKeyPubId);
        agentRepository.delete(agent);

        log.info("Deleted agent for apiKeyPubId={}", apiKeyPubId);
    }

    private AgenticTeam resolveTeam(Long agenticTeamId) {
        if (agenticTeamId == null) {
            return null;
        }
        return agenticTeamRepository.findById(agenticTeamId).orElse(null);
    }

    private void validateWebhookFields(String triggersTo, String webhookUrl) {
        if ("webhook".equals(triggersTo) && (webhookUrl == null || webhookUrl.isBlank())) {
            throw new BadRequestStatusException("webhookUrl is required when triggersTo is 'webhook'");
        }
    }
}
