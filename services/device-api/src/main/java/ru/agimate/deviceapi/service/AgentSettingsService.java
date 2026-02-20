package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.api.dto.AgentConfigResponse;
import ru.agimate.deviceapi.controller.manage.dto.AgentSettingsResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentSettingsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentSettingsRequest;
import ru.agimate.deviceapi.database.entities.AgentSettings;
import ru.agimate.deviceapi.database.entities.AgentTool;
import ru.agimate.deviceapi.database.entities.AgentTrigger;
import ru.agimate.deviceapi.database.entities.AgenticTeam;
import ru.agimate.deviceapi.database.repositories.AgentSettingsRepository;
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
public class AgentSettingsService {

    private final AgentSettingsRepository agentSettingsRepository;
    private final AgentToolRepository agentToolRepository;
    private final AgentTriggerRepository agentTriggerRepository;
    private final AgenticTeamRepository agenticTeamRepository;

    public List<AgentSettingsResponse> getAllForUser(UUID userPubId, UUID agenticTeamPubId) {
        List<AgentSettings> settingsList;
        if (agenticTeamPubId != null) {
            AgenticTeam team = agenticTeamRepository.findByPubId(agenticTeamPubId)
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            settingsList = agentSettingsRepository.findByUserPubIdAndAgenticTeamId(userPubId, team.getId());
        } else {
            settingsList = agentSettingsRepository.findByUserPubId(userPubId);
        }

        List<Long> teamIds = settingsList.stream()
                .map(AgentSettings::getAgenticTeamId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        Map<Long, AgenticTeam> teamsById = agenticTeamRepository.findAllById(teamIds).stream()
                .collect(Collectors.toMap(AgenticTeam::getId, Function.identity()));

        return settingsList.stream()
                .map(settings -> {
                    var tools = agentToolRepository.findByApiKeyPubId(settings.getApiKeyPubId());
                    var triggers = agentTriggerRepository.findByApiKeyPubId(settings.getApiKeyPubId());
                    var team = settings.getAgenticTeamId() != null ? teamsById.get(settings.getAgenticTeamId()) : null;
                    return AgentSettingsResponse.from(settings, tools, triggers, team);
                })
                .toList();
    }

    public AgentSettingsResponse getByApiKeyPubId(UUID apiKeyPubId) {
        AgentSettings settings = agentSettingsRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent settings not found"));
        var tools = agentToolRepository.findByApiKeyPubId(apiKeyPubId);
        var triggers = agentTriggerRepository.findByApiKeyPubId(apiKeyPubId);
        var team = resolveTeam(settings.getAgenticTeamId());
        return AgentSettingsResponse.from(settings, tools, triggers, team);
    }

    public AgentConfigResponse getConfigByApiKeyPubId(UUID apiKeyPubId) {
        AgentSettings settings = agentSettingsRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent settings not found"));
        var tools = agentToolRepository.findByApiKeyPubId(apiKeyPubId);
        var triggers = agentTriggerRepository.findByApiKeyPubId(apiKeyPubId);
        return new AgentConfigResponse(
                settings.getApiKeyPubId(),
                settings.getPrompt(),
                tools.stream().map(AgentTool::getToolName).toList(),
                triggers.stream().map(AgentTrigger::getTriggerName).toList()
        );
    }

    @Transactional
    public AgentSettingsResponse create(UUID userPubId, CreateAgentSettingsRequest request) {
        validateWebhookFields(request.triggersTo(), request.webhookUrl());

        AgenticTeam team = null;
        if (request.agenticTeamPubId() != null) {
            team = agenticTeamRepository.findByPubId(request.agenticTeamPubId())
                    .orElseThrow(() -> new NotFoundStatusException("Agentic team not found"));
            if (!team.getUserPubId().equals(userPubId)) {
                throw new ForbiddenStatusException("Access denied to the specified team");
            }
        }

        AgentSettings settings = AgentSettings.builder()
                .apiKeyPubId(request.apiKeyPubId())
                .userPubId(userPubId)
                .prompt(request.prompt())
                .triggersAllowAll(request.triggersAllowAll())
                .triggersTo(request.triggersTo())
                .webhookUrl(request.webhookUrl())
                .webhookAuthHeader(request.webhookAuthHeader())
                .agenticTeamId(team != null ? team.getId() : null)
                .build();
        settings = agentSettingsRepository.save(settings);

        List<AgentTool> tools = createTools(userPubId, request.apiKeyPubId(), request.tools());
        List<AgentTrigger> triggers = createTriggers(userPubId, request.apiKeyPubId(), request.triggers());

        log.info("Created agent settings for apiKeyPubId={}, user={}", request.apiKeyPubId(), userPubId);
        return AgentSettingsResponse.from(settings, tools, triggers, team);
    }

    @Transactional
    public AgentSettingsResponse update(UUID apiKeyPubId, UUID userPubId, UpdateAgentSettingsRequest request) {
        AgentSettings settings = agentSettingsRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent settings not found"));

        if (!settings.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        validateWebhookFields(request.triggersTo(), request.webhookUrl());

        settings.setPrompt(request.prompt());
        settings.setTriggersAllowAll(request.triggersAllowAll());
        settings.setTriggersTo(request.triggersTo());
        settings.setWebhookUrl(request.webhookUrl());
        settings.setWebhookAuthHeader(request.webhookAuthHeader());
        settings = agentSettingsRepository.save(settings);

        agentToolRepository.deleteByApiKeyPubId(apiKeyPubId);
        List<AgentTool> tools = createTools(userPubId, apiKeyPubId, request.tools());

        agentTriggerRepository.deleteByApiKeyPubId(apiKeyPubId);
        List<AgentTrigger> triggers = createTriggers(userPubId, apiKeyPubId, request.triggers());

        var team = resolveTeam(settings.getAgenticTeamId());

        log.info("Updated agent settings for apiKeyPubId={}", apiKeyPubId);
        return AgentSettingsResponse.from(settings, tools, triggers, team);
    }

    @Transactional
    public void delete(UUID apiKeyPubId, UUID userPubId) {
        AgentSettings settings = agentSettingsRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent settings not found"));

        if (!settings.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        agentToolRepository.deleteByApiKeyPubId(apiKeyPubId);
        agentTriggerRepository.deleteByApiKeyPubId(apiKeyPubId);
        agentSettingsRepository.delete(settings);

        log.info("Deleted agent settings for apiKeyPubId={}", apiKeyPubId);
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
