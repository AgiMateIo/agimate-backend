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

    public Page<AgentSkillResponse> getAgentSkills(UUID agentPubId, UUID userPubId, int page, int size) {
        verifyAgentOwnership(agentPubId, userPubId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<AgentSkill> agentSkills = agentSkillRepository.findByAgentPubId(agentPubId, pageRequest);

        var skillPubIds = agentSkills.getContent().stream()
                .map(AgentSkill::getSkillPubId)
                .collect(Collectors.toSet());

        Map<UUID, Skill> skillMap = skillPubIds.isEmpty()
                ? Map.of()
                : skillRepository.findByPubIdInNotDeleted(skillPubIds).stream()
                        .collect(Collectors.toMap(Skill::getPubId, s -> s));

        return agentSkills.map(as -> {
            Skill skill = skillMap.get(as.getSkillPubId());
            String name = skill != null ? skill.getName() : null;
            boolean needsReinstall = skill != null
                    && (as.getInstalledSkillVersion() == null || skill.getVersion() > as.getInstalledSkillVersion());
            return AgentSkillResponse.from(as, name, needsReinstall);
        });
    }

    public Page<AgentSkillWithConnectorsResponse> getAgentSkillsWithConnectors(UUID agentPubId, UUID userPubId, int page, int size) {
        verifyAgentOwnership(agentPubId, userPubId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Page<UUID> skillPubIdsPage = agentSkillRepository.findSkillPubIdsByAgentPubId(agentPubId, pageRequest);

        Map<UUID, AgentSkillWithConnectorsResponse> resolved = resolveSkillsByPubId(skillPubIdsPage.getContent());

        return skillPubIdsPage.map(pubId -> resolved.getOrDefault(pubId,
                new AgentSkillWithConnectorsResponse(pubId, null, null, List.of())));
    }

    /**
     * Aggregate skill name/description and attached connectors for the given pubIds.
     * Caller is responsible for any authorization — this method has no ownership check.
     * Soft-deleted skills are filtered out at the JPQL level.
     */
    public Map<UUID, AgentSkillWithConnectorsResponse> resolveSkillsByPubId(List<UUID> skillPubIds) {
        if (skillPubIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> nameByPubId = new HashMap<>();
        Map<UUID, String> descriptionByPubId = new HashMap<>();
        Map<UUID, List<SkillConnectorResponse>> connectorsByPubId = new HashMap<>();

        for (Object[] row : skillRepository.findNamesAndConnectorsByPubIdIn(skillPubIds)) {
            UUID pubId = (UUID) row[0];
            String name = (String) row[1];
            String description = (String) row[2];
            SkillConnector sc = (SkillConnector) row[3];
            nameByPubId.putIfAbsent(pubId, name);
            descriptionByPubId.putIfAbsent(pubId, description);
            List<SkillConnectorResponse> bucket = connectorsByPubId.computeIfAbsent(pubId, k -> new ArrayList<>());
            if (sc != null) {
                bucket.add(SkillConnectorResponse.from(sc));
            }
        }

        Map<UUID, AgentSkillWithConnectorsResponse> result = new HashMap<>();
        for (UUID pubId : nameByPubId.keySet()) {
            result.put(pubId, new AgentSkillWithConnectorsResponse(
                    pubId,
                    nameByPubId.get(pubId),
                    descriptionByPubId.get(pubId),
                    connectorsByPubId.getOrDefault(pubId, List.of())
            ));
        }
        return result;
    }

    @Transactional
    public AgentSkillResponse create(UUID agentPubId, UUID skillPubId, UUID userPubId) {
        verifyAgentOwnership(agentPubId, userPubId);
        var skill = verifySkillOwnership(skillPubId, userPubId);

        AgentSkill agentSkill = AgentSkill.builder()
                .userPubId(userPubId)
                .agentPubId(agentPubId)
                .skillPubId(skillPubId)
                .installedSkillVersion(skill.getVersion())
                .build();

        try {
            agentSkill = agentSkillRepository.save(agentSkill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill is already bound to this agent");
        }

        agentSkillPolicyService.applyDiff(agentPubId, userPubId);

        log.info("Bound skill {} to agent {} for user {}", skillPubId, agentPubId, userPubId);
        return AgentSkillResponse.from(agentSkill, skill.getName(), false);
    }

    @Transactional
    public void delete(UUID agentPubId, UUID skillPubId, UUID userPubId) {
        verifyAgentOwnership(agentPubId, userPubId);

        AgentSkill agentSkill = agentSkillRepository.findByAgentPubIdAndSkillPubId(agentPubId, skillPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent-skill binding not found"));

        if (!agentSkill.getUserPubId().equals(userPubId)) {
            throw new NotFoundStatusException("Agent-skill binding not found");
        }

        agentSkillRepository.delete(agentSkill);
        agentSkillPolicyService.applyDiff(agentPubId, userPubId);

        log.info("Unbound skill {} from agent {} for user {}", skillPubId, agentPubId, userPubId);
    }

    @Transactional
    public void syncPolicies(UUID agentPubId, UUID userPubId) {
        verifyAgentOwnership(agentPubId, userPubId);

        agentSkillPolicyService.applyDiff(agentPubId, userPubId);

        // Update installedSkillVersion for all skills on this agent
        var agentSkills = agentSkillRepository.findByAgentPubId(agentPubId);
        var skillPubIds = agentSkills.stream().map(AgentSkill::getSkillPubId).collect(Collectors.toSet());

        Map<UUID, Integer> skillVersions = skillPubIds.isEmpty()
                ? Map.of()
                : skillRepository.findByPubIdInNotDeleted(skillPubIds).stream()
                        .collect(Collectors.toMap(Skill::getPubId, Skill::getVersion));

        for (AgentSkill as : agentSkills) {
            Integer currentVersion = skillVersions.get(as.getSkillPubId());
            if (currentVersion != null) {
                as.setInstalledSkillVersion(currentVersion);
            }
        }
        agentSkillRepository.saveAll(agentSkills);

        log.info("Synced policies for all skills on agent {} for user {}", agentPubId, userPubId);
    }

    public PolicyDiffResponse previewPolicyDiff(UUID agentPubId, UUID skillPubId, UUID userPubId, String action) {
        verifyAgentOwnership(agentPubId, userPubId);

        return switch (action) {
            case "add" -> {
                verifySkillOwnership(skillPubId, userPubId);
                yield agentSkillPolicyService.previewAdd(agentPubId, skillPubId);
            }
            case "remove" -> {
                verifySkillOwnership(skillPubId, userPubId);
                yield agentSkillPolicyService.previewRemove(agentPubId, skillPubId);
            }
            case "sync" -> agentSkillPolicyService.previewSync(agentPubId);
            default -> throw new IllegalArgumentException("Invalid action: " + action + ". Expected: add, remove, sync");
        };
    }

    public Skill findAssignedSkill(UUID agentPubId, UUID skillPubId, UUID userPubId) {
        agentSkillRepository.findByAgentPubIdAndSkillPubId(agentPubId, skillPubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));

        return verifySkillOwnership(skillPubId, userPubId);
    }

    private void verifyAgentOwnership(UUID agentPubId, UUID userPubId) {
        var agent = agentRepository.findByPubId(agentPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserPubId().equals(userPubId)) {
            throw new NotFoundStatusException("Agent not found");
        }
    }

    private Skill verifySkillOwnership(UUID skillPubId, UUID userPubId) {
        var skill = skillRepository.findByPubIdNotDeleted(skillPubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId)) {
            throw new NotFoundStatusException("Skill not found");
        }
        return skill;
    }
}
