package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.api.dto.AgentConfigResponse;
import ru.agimate.deviceapi.controller.manage.dto.AgentResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentRequest;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.AgentTool;
import ru.agimate.deviceapi.database.entities.AgentTrigger;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentToolRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerRepository;
import ru.agimate.deviceapi.database.repositories.AgenticTeamRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentService {

    private final AgentRepository agentRepository;
    private final AgentToolRepository agentToolRepository;
    private final AgentTriggerRepository agentTriggerRepository;
    private final AgenticTeamRepository agenticTeamRepository;

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
                    var tools = agentToolRepository.findByApiKeyPubId(agent.getApiKeyPubId());
                    var triggers = agentTriggerRepository.findByApiKeyPubId(agent.getApiKeyPubId());
                    var team = agent.getAgenticTeamId() != null ? teamsById.get(agent.getAgenticTeamId()) : null;
                    return AgentResponse.from(agent, tools, triggers, team);
                })
                .toList();
    }

    public AgentResponse getByApiKeyPubId(UUID apiKeyPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        var tools = agentToolRepository.findByApiKeyPubId(apiKeyPubId);
        var triggers = agentTriggerRepository.findByApiKeyPubId(apiKeyPubId);
        var team = resolveTeam(agent.getAgenticTeamId());
        return AgentResponse.from(agent, tools, triggers, team);
    }

    public AgentConfigResponse getConfigByApiKeyPubId(UUID apiKeyPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        var tools = agentToolRepository.findByApiKeyPubId(apiKeyPubId);
        var triggers = agentTriggerRepository.findByApiKeyPubId(apiKeyPubId);
        return new AgentConfigResponse(
                agent.getApiKeyPubId(),
                agent.getPrompt(),
                tools.stream().map(AgentTool::getToolName).toList(),
                triggers.stream().map(AgentTrigger::getTriggerName).toList()
        );
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

        List<AgentTool> tools = createTools(userPubId, request.apiKeyPubId(), request.tools());
        List<AgentTrigger> triggers = createTriggers(userPubId, request.apiKeyPubId(), request.triggers());

        log.info("Created agent for apiKeyPubId={}, user={}", request.apiKeyPubId(), userPubId);
        return AgentResponse.from(agent, tools, triggers, team);
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

        agentToolRepository.deleteByApiKeyPubId(apiKeyPubId);
        List<AgentTool> tools = createTools(userPubId, apiKeyPubId, request.tools());

        agentTriggerRepository.deleteByApiKeyPubId(apiKeyPubId);
        List<AgentTrigger> triggers = createTriggers(userPubId, apiKeyPubId, request.triggers());

        var team = resolveTeam(agent.getAgenticTeamId());

        log.info("Updated agent for apiKeyPubId={}", apiKeyPubId);
        return AgentResponse.from(agent, tools, triggers, team);
    }

    @Transactional
    public void delete(UUID apiKeyPubId, UUID userPubId) {
        Agent agent = agentRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));

        if (!agent.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        agentToolRepository.deleteByApiKeyPubId(apiKeyPubId);
        agentTriggerRepository.deleteByApiKeyPubId(apiKeyPubId);
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

    private List<AgentTool> createTools(UUID userPubId, UUID apiKeyPubId, List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        List<AgentTool> tools = toolNames.stream()
                .distinct()
                .map(name -> AgentTool.builder()
                        .userPubId(userPubId)
                        .apiKeyPubId(apiKeyPubId)
                        .toolName(name)
                        .build())
                .toList();
        return agentToolRepository.saveAll(tools);
    }

    private List<AgentTrigger> createTriggers(UUID userPubId, UUID apiKeyPubId, List<String> triggerNames) {
        if (triggerNames == null || triggerNames.isEmpty()) {
            return List.of();
        }
        List<AgentTrigger> triggers = triggerNames.stream()
                .distinct()
                .map(name -> AgentTrigger.builder()
                        .userPubId(userPubId)
                        .apiKeyPubId(apiKeyPubId)
                        .triggerName(name)
                        .build())
                .toList();
        return agentTriggerRepository.saveAll(triggers);
    }
}
