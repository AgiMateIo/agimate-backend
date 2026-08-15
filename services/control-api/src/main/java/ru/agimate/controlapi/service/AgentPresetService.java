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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        return toResponses(agentPresetRepository.findAllByEnabledTrueOrderBySortOrderAscNameAsc());
    }

    /** Every preset, disabled ones included — for the admin table. */
    public List<AgentPresetResponse> listAll() {
        return toResponses(agentPresetRepository.findAllByOrderBySortOrderAscNameAsc());
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
                .agentType(request.agentType())
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
        if (request.agentType() != null) {
            preset.setAgentType(request.agentType());
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
        Map<String, Skill> known = loadSystemSkills(names);
        List<String> missing = names.stream()
                .filter(name -> !known.containsKey(name))
                .distinct()
                .toList();
        if (!missing.isEmpty()) {
            throw new BadRequestStatusException("Unknown system skill(s): " + String.join(", ", missing));
        }
    }

    /** The whole page's skills in one lookup: a preset references them by name, and the names repeat across presets. */
    private List<AgentPresetResponse> toResponses(List<AgentPreset> presets) {
        Map<String, Skill> skillsByName = loadSystemSkills(presets.stream()
                .flatMap(preset -> preset.getSkillNames().stream())
                .collect(Collectors.toSet()));
        return presets.stream()
                .map(preset -> toResponse(preset, skillsByName))
                .toList();
    }

    private Map<String, Skill> loadSystemSkills(Collection<String> names) {
        if (names.isEmpty()) {
            return Map.of();
        }
        // (SYSTEM_USER_ID, name) is unique among the live rows — uq_skills_user_id_name_active.
        return skillRepository.findByUserIdAndNameInNotDeleted(SYSTEM_USER_ID, names).stream()
                .collect(Collectors.toMap(Skill::getName, Function.identity()));
    }

    private AgentPresetResponse toResponse(AgentPreset preset) {
        return toResponse(preset, loadSystemSkills(preset.getSkillNames()));
    }

    private AgentPresetResponse toResponse(AgentPreset preset, Map<String, Skill> skillsByName) {
        List<AgentPresetResponse.PresetSkill> skills = new ArrayList<>();
        LinkedHashSet<String> connectorCodes = new LinkedHashSet<>();
        for (String skillName : preset.getSkillNames()) {
            Skill skill = skillsByName.get(skillName);
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
                preset.getAgentType(),
                preset.getSortOrder(),
                preset.isEnabled());
    }
}
