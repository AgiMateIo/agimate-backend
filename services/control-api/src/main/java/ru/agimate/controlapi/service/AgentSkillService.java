package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.controller.agent.dto.AgentSkillWithConnectorsResponse;
import ru.agimate.controlapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.controlapi.controller.manage.dto.PolicyDiffResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillConnectorStatus;
import ru.agimate.controlapi.database.entities.AgentSkill;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.AgentSkillRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;

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
    private final ConnectionRepository connectionRepository;
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

        Map<String, UUID> agentConnections = agentConnectionsByCode(agentId);

        return agentSkills.map(as -> {
            Skill skill = skillMap.get(as.getSkillId());
            String name = skill != null ? skill.getName() : null;
            boolean needsReinstall = skill != null
                    && (as.getInstalledSkillVersion() == null || skill.getVersion() > as.getInstalledSkillVersion());
            List<SkillConnectorStatus> connectors = skill == null ? List.of()
                    : skill.getConnectorCodes().stream()
                            .map(code -> new SkillConnectorStatus(code, agentConnections.get(code)))
                            .toList();
            return AgentSkillResponse.from(as, name, connectors, needsReinstall);
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
     * Aggregate skill name/description and required connector codes for the given ids.
     * Caller is responsible for any authorization — this method has no ownership check.
     * Soft-deleted skills are filtered out.
     */
    public Map<UUID, AgentSkillWithConnectorsResponse> resolveSkillsById(List<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, AgentSkillWithConnectorsResponse> result = new HashMap<>();
        for (Skill skill : skillRepository.findByIdInNotDeleted(skillIds)) {
            result.put(skill.getId(), new AgentSkillWithConnectorsResponse(
                    skill.getId(),
                    skill.getName(),
                    skill.getDescription(),
                    skill.getConnectorCodes()
            ));
        }
        return result;
    }

    @Transactional
    public AgentSkillResponse create(UUID agentId, UUID skillId, UUID userId) {
        verifyAgentOwnership(agentId, userId);
        Skill skill = verifySkillAccessible(skillId, userId);

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

        Map<String, UUID> agentConnections = agentConnectionsByCode(agentId);
        List<SkillConnectorStatus> connectors = skill.getConnectorCodes().stream()
                .map(code -> new SkillConnectorStatus(code, agentConnections.get(code)))
                .toList();
        return AgentSkillResponse.from(agentSkill, skill.getName(), connectors, false);
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
                verifySkillAccessible(skillId, userId);
                yield agentSkillPolicyService.previewAdd(agentId, skillId);
            }
            case "remove" -> {
                verifySkillAccessible(skillId, userId);
                yield agentSkillPolicyService.previewRemove(agentId, skillId);
            }
            case "sync" -> agentSkillPolicyService.previewSync(agentId);
            default -> throw new IllegalArgumentException("Invalid action: " + action + ". Expected: add, remove, sync");
        };
    }

    /** Активные коннекшены агента, отображённые connectorCode → connectionId (первый по порядку на код). */
    private Map<String, UUID> agentConnectionsByCode(UUID agentId) {
        Map<String, UUID> byCode = new HashMap<>();
        for (Connection connection : connectionRepository.findActiveBoundToAgent(agentId)) {
            byCode.putIfAbsent(connection.getConnectorCode(), connection.getId());
        }
        return byCode;
    }

    private void verifyAgentOwnership(UUID agentId, UUID userId) {
        var agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserId().equals(userId)) {
            throw new NotFoundStatusException("Agent not found");
        }
    }

    /** Скилл доступен для привязки, если он свой или публичный (клонировать не требуется). */
    private Skill verifySkillAccessible(UUID skillId, UUID userId) {
        var skill = skillRepository.findByIdNotDeleted(skillId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }
}
