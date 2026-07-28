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
 * The gallery of role presets for the agent creation wizard. A preset is pure prefill: the frontend
 * fills the wizard's editable fields from it, and the final values arrive as an ordinary
 * {@code CreateAgentRequest}. Skill names are resolved into system skills at listing time; a skill that
 * has disappeared simply drops out of the response (with a warning in the log).
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

    /** Every preset, disabled ones included — for the admin table. */
    public List<AgentPresetResponse> listAll() {
        return agentPresetRepository.findAllByOrderBySortOrderAscNameAsc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public AgentPresetResponse create(UUID actorId, CreateAgentPresetRequest request) {
        if (agentPresetRepository.findByName(request.name()).isPresent()) {
            throw new ConflictStatusException("Preset with name '" + request.name() + "' already exists");
        }
        List<String> skillNames = request.resolveSkillNames();
        validateSkillNames(skillNames);

        AgentPreset preset = AgentPreset.builder()
                .name(request.name())
                .title(request.title())
                .description(request.description())
                .instructions(request.instructions())
                .skillNames(new ArrayList<>(skillNames))
                .sortOrder(request.resolveSortOrder())
                .enabled(true)
                .build();
        try {
            preset = agentPresetRepository.save(preset);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Preset with name '" + request.name() + "' already exists");
        }
        log.info("Created agent preset '{}' id={} by admin={}", preset.getName(), preset.getId(), actorId);
        return toResponse(preset);
    }

    @Transactional
    public AgentPresetResponse update(UUID actorId, UUID id, UpdateAgentPresetRequest request) {
        AgentPreset preset = agentPresetRepository.findById(id)
                .orElseThrow(() -> new NotFoundStatusException("Preset not found"));

        if (request.title() != null) {
            preset.setTitle(request.title());
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
        log.info("Updated agent preset '{}' id={} by admin={}", preset.getName(), id, actorId);
        return toResponse(preset);
    }

    /**
     * Every name must resolve to an existing system skill. Unlike the tolerant listing (where a missing
     * skill silently drops out), garbage is not let in at the input — symmetrically to the validation of
     * connector codes in {@code SkillService}.
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
                log.warn("Preset '{}' references missing system skill '{}'", preset.getName(), skillName);
                continue;
            }
            skills.add(new AgentPresetResponse.PresetSkill(skill.getId(), skill.getName(),
                    skill.getTitle() != null ? skill.getTitle() : skill.getName(), skill.getDescription()));
            connectorCodes.addAll(skill.getConnectorCodes());
        }
        return new AgentPresetResponse(
                preset.getId(),
                preset.getName(),
                preset.getTitle(),
                preset.getDescription(),
                preset.getInstructions(),
                skills,
                List.copyOf(connectorCodes),
                List.copyOf(preset.getSkillNames()),
                preset.getSortOrder(),
                preset.isEnabled());
    }
}
