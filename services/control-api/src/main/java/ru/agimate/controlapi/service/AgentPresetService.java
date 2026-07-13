package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.manage.dto.AgentPresetResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateAgentPresetRequest;
import ru.agimate.controlapi.controller.manage.dto.UpdateAgentPresetRequest;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

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

    /** Все пресеты, включая disabled — для админ-таблицы. */
    public List<AgentPresetResponse> listAll() {
        return agentPresetRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AgentPresetResponse create(UUID actorId, CreateAgentPresetRequest request) {
        if (agentPresetRepository.findByCode(request.code()).isPresent()) {
            throw new ConflictStatusException("Preset with code '" + request.code() + "' already exists");
        }
        List<String> skillNames = request.resolveSkillNames();
        validateSkillNames(skillNames);

        AgentPreset preset = AgentPreset.builder()
                .code(request.code())
                .name(request.name())
                .description(request.description())
                .instructions(request.instructions())
                .skillNames(new ArrayList<>(skillNames))
                .sortOrder(request.resolveSortOrder())
                .enabled(true)
                .build();
        try {
            preset = agentPresetRepository.save(preset);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Preset with code '" + request.code() + "' already exists");
        }
        log.info("Created agent preset '{}' id={} by admin={}", preset.getCode(), preset.getId(), actorId);
        return toResponse(preset);
    }

    @Transactional
    public AgentPresetResponse update(UUID actorId, UUID id, UpdateAgentPresetRequest request) {
        AgentPreset preset = agentPresetRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Preset not found"));

        if (request.name() != null) {
            preset.setName(request.name());
        }
        if (request.description() != null) {
            preset.setDescription(request.description());
        }
        if (request.instructions() != null) {
            preset.setInstructions(request.instructions());
        }
        if (request.skillNames() != null) {
            validateSkillNames(request.skillNames());
            preset.setSkillNames(new ArrayList<>(request.skillNames()));
        }
        if (request.sortOrder() != null) {
            preset.setSortOrder(request.sortOrder());
        }
        if (request.enabled() != null) {
            preset.setEnabled(request.enabled());
        }

        preset = agentPresetRepository.save(preset);
        log.info("Updated agent preset '{}' id={} by admin={}", preset.getCode(), id, actorId);
        return toResponse(preset);
    }

    /**
     * Каждое имя обязано резолвиться в существующий системный скилл. В отличие от толерантного
     * листинга (пропавший скилл молча выпадает), на входе мусор не пускаем — симметрично
     * валидации кодов коннекторов в {@code SkillService}.
     */
    private void validateSkillNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        List<String> missing = names.stream()
                .filter(name -> skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, name).isEmpty())
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            throw new BadRequestStatusException("Unknown system skill(s): " + String.join(", ", missing));
        }
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
                List.copyOf(connectorCodes),
                List.copyOf(preset.getSkillNames()),
                preset.getSortOrder(),
                preset.isEnabled());
    }
}
