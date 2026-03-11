package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.rest.error.ValidationErrorStatusException;
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
import ru.agimate.deviceapi.database.entities.TriggerDestination;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
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
    private final AgenticTeamRepository agenticTeamRepository;
    private final AppRepository appRepository;

    public Page<AgentResponse> getAllForUser(UUID userPubId, UUID agenticTeamPubId, int page, int size) {
        Page<Agent> agents;
        if (agenticTeamPubId != null) {
            AgenticTeam team = agenticTeamRepository.findByPubId(agenticTeamPubId)
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            agents = agentRepository.findByUserPubIdAndAgenticTeamId(userPubId, team.getId(), PageRequest.of(page, size));
        } else {
            agents = agentRepository.findByUserPubId(userPubId, PageRequest.of(page, size));
        }

        List<Long> teamIds = agents.getContent().stream()
                .map(Agent::getAgenticTeamId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, AgenticTeam> teamsById = agenticTeamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(AgenticTeam::getId, Function.identity()));

        return agents.map(agent -> {
            var team = agent.getAgenticTeamId() != null ? teamsById.get(agent.getAgenticTeamId()) : null;
            return AgentResponse.from(agent, team);
        });
    }

    public Agent findByPubId(UUID pubId) {
        return agentRepository.findByPubId(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
    }

    public AgentResponse getByPubId(UUID pubId) {
        Agent agent = findByPubId(pubId);
        var team = resolveTeam(agent.getAgenticTeamId());
        return AgentResponse.from(agent, team);
    }

    public AgentConfigResponse getConfigByPubId(UUID agentPubId) {
        Agent agent = findByPubId(agentPubId);

        var toolPolicies = agentToolPolicyRepository.findByAgentPubId(agentPubId);
        var triggerPolicies = agentTriggerPolicyRepository.findByAgentPubId(agentPubId);

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
                agent.getPubId(),
                agent.getPrompt(),
                toolDefinitions,
                triggerNames
        );
    }

    public List<ToolDefinition> getAvailableTools(UUID agentPubId) {
        Agent agent = findByPubId(agentPubId);

        var toolPolicies = agentToolPolicyRepository.findByAgentPubId(agentPubId);

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
    public AgentCreateResult create(UUID userPubId, CreateAgentRequest request) {
        TriggerDestination destination = request.triggerDestination() != null
                ? request.triggerDestination() : TriggerDestination.CENTRIFUGO;
        validateWebhookFields(destination, request.webhookUrl());

        AgenticTeam team = null;
        if (request.agenticTeamPubId() != null) {
            team = agenticTeamRepository.findByPubId(request.agenticTeamPubId())
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
                .prompt(request.prompt())
                .triggerDestination(destination)
                .webhookUrl(request.webhookUrl())
                .webhookAuthHeader(request.webhookAuthHeader())
                .agenticTeamId(team != null ? team.getId() : null)
                .build();
        agent = agentRepository.save(agent);

        log.info("Created agent pubId={}, user={}", agent.getPubId(), userPubId);
        return new AgentCreateResult(agent, team, generatedKey.fullKey());
    }

    @Transactional
    public AgentCreateResult regenerateKey(UUID pubId, UUID userPubId) {
        Agent agent = agentRepository.findByPubId(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        GeneratedAppKey generatedKey = AppKeyUtils.generate(AGENT_KEY_PREFIX);
        agent.setKeyHash(generatedKey.secretHash());
        agent.setKeyId(generatedKey.keyId());
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());

        log.info("Regenerated key for agent pubId={}", pubId);
        return new AgentCreateResult(agent, team, generatedKey.fullKey());
    }

    @Transactional
    public AgentResponse update(UUID pubId, UUID userPubId, UpdateAgentRequest request) {
        Agent agent = agentRepository.findByPubId(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        TriggerDestination destination = request.triggerDestination() != null
                ? request.triggerDestination() : agent.getTriggerDestination();
        validateWebhookFields(destination, request.webhookUrl());

        if (request.name() != null) {
            agent.setName(request.name());
        }
        agent.setPrompt(request.prompt());
        agent.setTriggerDestination(destination);
        agent.setWebhookUrl(request.webhookUrl());
        agent.setWebhookAuthHeader(request.webhookAuthHeader());
        if (request.enabled() != null) {
            agent.setEnabled(request.enabled());
        }
        agent = agentRepository.save(agent);

        var team = resolveTeam(agent.getAgenticTeamId());

        log.info("Updated agent pubId={}", pubId);
        return AgentResponse.from(agent, team);
    }

    @Transactional
    public void delete(UUID pubId, UUID userPubId) {
        Agent agent = agentRepository.findByPubId(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        agentToolPolicyRepository.deleteByAgentPubId(agent.getPubId());
        agentTriggerPolicyRepository.deleteByAgentPubId(agent.getPubId());
        agentRepository.delete(agent);

        log.info("Deleted agent pubId={}", pubId);
    }

    private AgenticTeam resolveTeam(Long agenticTeamId) {
        if (agenticTeamId == null) {
            return null;
        }
        return agenticTeamRepository.findById(agenticTeamId).orElse(null);
    }

    private void validateWebhookFields(TriggerDestination destination, String webhookUrl) {
        if (destination == TriggerDestination.WEBHOOK && (webhookUrl == null || webhookUrl.isBlank())) {
            throw new ValidationErrorStatusException("webhookUrl", "Webhook url is required when triggerDestination is WEBHOOK");
        }
    }

    public record AgentCreateResult(Agent agent, AgenticTeam team, String plaintextKey) {}
}
