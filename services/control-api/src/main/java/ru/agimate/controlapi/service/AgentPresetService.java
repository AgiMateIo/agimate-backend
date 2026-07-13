package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.controller.manage.dto.AgentPresetResponse;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

/**
 * Галерея пресетов ролей для мастера создания агента. Пресет — чистый префилл: фронт заполняет им
 * редактируемые поля мастера, финальные значения приходят обычным {@code CreateAgentRequest}.
 * Имена скилов резолвятся в системные скилы на момент листинга; исчезнувший скилл просто
 * выпадает из ответа (warning в лог).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentPresetService {

    private final AgentPresetRepository agentPresetRepository;
    private final SkillRepository skillRepository;

    public List<AgentPresetResponse> list() {
        return agentPresetRepository.findAllByEnabledTrueOrderBySortOrderAscNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    private AgentPresetResponse toResponse(AgentPreset preset) {
        List<AgentPresetResponse.PresetSkill> skills = new ArrayList<>();
        LinkedHashSet<String> connectorCodes = new LinkedHashSet<>();
        for (String skillName : preset.getSkillNames()) {
            Skill skill = skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, skillName).orElse(null);
            if (skill == null) {
                log.warn("Preset '{}' references missing system skill '{}'", preset.getCode(), skillName);
                continue;
            }
            skills.add(new AgentPresetResponse.PresetSkill(skill.getId(), skill.getName(), skill.getDescription()));
            connectorCodes.addAll(skill.getConnectorCodes());
        }
        return new AgentPresetResponse(
                preset.getId(),
                preset.getCode(),
                preset.getName(),
                preset.getDescription(),
                preset.getInstructions(),
                skills,
                List.copyOf(connectorCodes));
    }
}
