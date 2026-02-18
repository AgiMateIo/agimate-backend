package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.api.dto.AgentConfigResponse;
import ru.agimate.deviceapi.controller.manage.dto.AgentSettingsResponse;
import ru.agimate.deviceapi.controller.manage.dto.CreateAgentSettingsRequest;
import ru.agimate.deviceapi.controller.manage.dto.UpdateAgentSettingsRequest;
import ru.agimate.deviceapi.database.entities.AgentSettings;
import ru.agimate.deviceapi.database.entities.AgentTool;
import ru.agimate.deviceapi.database.entities.AgentTrigger;
import ru.agimate.deviceapi.database.repositories.AgentSettingsRepository;
import ru.agimate.deviceapi.database.repositories.AgentToolRepository;
import ru.agimate.deviceapi.database.repositories.AgentTriggerRepository;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSettingsService {

    private final AgentSettingsRepository agentSettingsRepository;
    private final AgentToolRepository agentToolRepository;
    private final AgentTriggerRepository agentTriggerRepository;

    public List<AgentSettingsResponse> getAllForUser(UUID userPubId) {
        List<AgentSettings> settingsList = agentSettingsRepository.findByUserPubId(userPubId);
        return settingsList.stream()
                .map(settings -> {
                    var tools = agentToolRepository.findByApiKeyPubId(settings.getApiKeyPubId());
                    var triggers = agentTriggerRepository.findByApiKeyPubId(settings.getApiKeyPubId());
                    return AgentSettingsResponse.from(settings, tools, triggers);
                })
                .toList();
    }

    public AgentSettingsResponse getByApiKeyPubId(UUID apiKeyPubId) {
        AgentSettings settings = agentSettingsRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent settings not found"));
        var tools = agentToolRepository.findByApiKeyPubId(apiKeyPubId);
        var triggers = agentTriggerRepository.findByApiKeyPubId(apiKeyPubId);
        return AgentSettingsResponse.from(settings, tools, triggers);
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
        AgentSettings settings = AgentSettings.builder()
                .apiKeyPubId(request.apiKeyPubId())
                .userPubId(userPubId)
                .prompt(request.prompt())
                .triggersAllowAll(request.triggersAllowAll())
                .triggersTo(request.triggersTo())
                .build();
        settings = agentSettingsRepository.save(settings);

        List<AgentTool> tools = createTools(userPubId, request.apiKeyPubId(), request.tools());
        List<AgentTrigger> triggers = createTriggers(userPubId, request.apiKeyPubId(), request.triggers());

        log.info("Created agent settings for apiKeyPubId={}, user={}", request.apiKeyPubId(), userPubId);
        return AgentSettingsResponse.from(settings, tools, triggers);
    }

    @Transactional
    public AgentSettingsResponse update(UUID apiKeyPubId, UUID userPubId, UpdateAgentSettingsRequest request) {
        AgentSettings settings = agentSettingsRepository.findByApiKeyPubId(apiKeyPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent settings not found"));

        if (!settings.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }

        settings.setPrompt(request.prompt());
        settings.setTriggersAllowAll(request.triggersAllowAll());
        settings.setTriggersTo(request.triggersTo());
        settings = agentSettingsRepository.save(settings);

        agentToolRepository.deleteByApiKeyPubId(apiKeyPubId);
        List<AgentTool> tools = createTools(userPubId, apiKeyPubId, request.tools());

        agentTriggerRepository.deleteByApiKeyPubId(apiKeyPubId);
        List<AgentTrigger> triggers = createTriggers(userPubId, apiKeyPubId, request.triggers());

        log.info("Updated agent settings for apiKeyPubId={}", apiKeyPubId);
        return AgentSettingsResponse.from(settings, tools, triggers);
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

    private List<AgentTool> createTools(UUID userPubId, UUID apiKeyPubId, List<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        List<AgentTool> tools = toolNames.stream()
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
                .map(name -> AgentTrigger.builder()
                        .userPubId(userPubId)
                        .apiKeyPubId(apiKeyPubId)
                        .triggerName(name)
                        .build())
                .toList();
        return agentTriggerRepository.saveAll(triggers);
    }
}
