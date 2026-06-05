package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.deviceapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.deviceapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.deviceapi.controller.manage.dto.SkillConnectorResponse;
import ru.agimate.deviceapi.database.entities.AgentSkill;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.database.entities.SkillConnector;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentSkillRepository;
import ru.agimate.deviceapi.database.repositories.SkillRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSkillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSkillRepository agentSkillRepository;
    private final AgentRepository agentRepository;
    private final SkillRepository skillRepository;
    private final AgentSkillPolicyService agentSkillPolicyService;

    public Page<AgentSkillResponse> getAgentSkills(UUID agentId, UUID userId, int page, int size) {
        verifyAgentOwnership(agentId, userId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<AgentSkill> agentSkills = agentSkillRepository.findByAgentId(agentId, pageRequest);

        var skillIds = agentSkills.getContent().stream()
                .map(AgentSkill::getSkillId)
                .collect(Collectors.toSet());

        Map<UUID, Skill> skillMap = skillIds.isEmpty()
                ? Map.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, s -> s));

        return agentSkills.map(as -> {
            Skill skill = skillMap.get(as.getSkillId());
            String name = skill != null ? skill.getName() : null;
            boolean needsReinstall = skill != null
                    && (as.getInstalledSkillVersion() == null || skill.getVersion() > as.getInstalledSkillVersion());
            return AgentSkillResponse.from(as, name, needsReinstall);
        });
    }

    public Page<AgentSkillWithConnectorsResponse> getAgentSkillsWithConnectors(UUID agentId, UUID userId, int page, int size) {
        verifyAgentOwnership(agentId, userId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<UUID> skillIdsPage = agentSkillRepository.findSkillIdsByAgentId(agentId, pageRequest);

        Map<UUID, AgentSkillWithConnectorsResponse> resolved = resolveSkillsById(skillIdsPage.getContent());

        return skillIdsPage.map(id -> resolved.getOrDefault(id,
                new AgentSkillWithConnectorsResponse(id, null, null, List.of())));
    }

    /**
     * Aggregate skill name/description and attached connectors for the given ids.
     * Caller is responsible for any authorization — this method has no ownership check.
     * Soft-deleted skills are filtered out at the JPQL level.
     */
    public Map<UUID, AgentSkillWithConnectorsResponse> resolveSkillsById(List<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> nameById = new HashMap<>();
        Map<UUID, String> descriptionById = new HashMap<>();
        Map<UUID, List<SkillConnectorResponse>> connectorsById = new HashMap<>();

        for (Object[] row : skillRepository.findNamesAndConnectorsByIdIn(skillIds)) {
            UUID id = (UUID) row[0];
            String name = (String) row[1];
            String description = (String) row[2];
            SkillConnector sc = (SkillConnector) row[3];
            nameById.putIfAbsent(id, name);
            descriptionById.putIfAbsent(id, description);
            List<SkillConnectorResponse> bucket = connectorsById.computeIfAbsent(id, k -> new ArrayList<>());
            if (sc != null) {
                bucket.add(SkillConnectorResponse.from(sc));
            }
        }

        Map<UUID, AgentSkillWithConnectorsResponse> result = new HashMap<>();
        for (UUID id : nameById.keySet()) {
            result.put(id, new AgentSkillWithConnectorsResponse(
                    id,
                    nameById.get(id),
                    descriptionById.get(id),
                    connectorsById.getOrDefault(id, List.of())
            ));
        }
        return result;
    }

    @Transactional
    public AgentSkillResponse create(UUID agentId, UUID skillId, UUID userId) {
        verifyAgentOwnership(agentId, userId);
        var skill = verifySkillOwnership(skillId, userId);

        AgentSkill agentSkill = AgentSkill.builder()
                .userId(userId)
                .agentId(agentId)
                .skillId(skillId)
                .installedSkillVersion(skill.getVersion())
                .build();

        try {
            agentSkill = agentSkillRepository.save(agentSkill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill is already bound to this agent");
        }

        agentSkillPolicyService.applyDiff(agentId, userId);

        log.info("Bound skill {} to agent {} for user {}", skillId, agentId, userId);
        return AgentSkillResponse.from(agentSkill, skill.getName(), false);
    }

    @Transactional
    public void delete(UUID agentId, UUID skillId, UUID userId) {
        verifyAgentOwnership(agentId, userId);

        AgentSkill agentSkill = agentSkillRepository.findByAgentIdAndSkillId(agentId, skillId)
                .orElseThrow(() -> new NotFoundStatusException("Agent-skill binding not found"));

        if (!agentSkill.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent-skill binding not found");
        }

        agentSkillRepository.delete(agentSkill);
        agentSkillPolicyService.applyDiff(agentId, userId);

        log.info("Unbound skill {} from agent {} for user {}", skillId, agentId, userId);
    }

    @Transactional
    public void syncPolicies(UUID agentId, UUID userId) {
        verifyAgentOwnership(agentId, userId);

        agentSkillPolicyService.applyDiff(agentId, userId);

        // Update installedSkillVersion for all skills on this agent
        var agentSkills = agentSkillRepository.findByAgentId(agentId);
        var skillIds = agentSkills.stream().map(AgentSkill::getSkillId).collect(Collectors.toSet());

        Map<UUID, Integer> skillVersions = skillIds.isEmpty()
                ? Map.of()
                : skillRepository.findByIdInNotDeleted(skillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, Skill::getVersion));

        for (AgentSkill as : agentSkills) {
            Integer currentVersion = skillVersions.get(as.getSkillId());
            if (currentVersion != null) {
                as.setInstalledSkillVersion(currentVersion);
            }
        }
        agentSkillRepository.saveAll(agentSkills);

        log.info("Synced policies for all skills on agent {} for user {}", agentId, userId);
    }

    public PolicyDiffResponse previewPolicyDiff(UUID agentId, UUID skillId, UUID userId, String action) {
        verifyAgentOwnership(agentId, userId);

        return switch (action) {
            case "add" -> {
                verifySkillOwnership(skillId, userId);
                yield agentSkillPolicyService.previewAdd(agentId, skillId);
            }
            case "remove" -> {
                verifySkillOwnership(skillId, userId);
                yield agentSkillPolicyService.previewRemove(agentId, skillId);
            }
            case "sync" -> agentSkillPolicyService.previewSync(agentId);
            default -> throw new IllegalArgumentException("Invalid action: " + action + ". Expected: add, remove, sync");
        };
    }

    public Skill findAssignedSkill(UUID agentId, UUID skillId, UUID userId) {
        agentSkillRepository.findByAgentIdAndSkillId(agentId, skillId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));

        return verifySkillOwnership(skillId, userId);
    }

    private void verifyAgentOwnership(UUID agentId, UUID userId) {
        var agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
    }

    private Skill verifySkillOwnership(UUID skillId, UUID userId) {
        var skill = skillRepository.findByIdNotDeleted(skillId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Skill not found");
        }
        return skill;
    }
}
