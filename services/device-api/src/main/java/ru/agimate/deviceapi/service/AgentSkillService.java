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
import ru.agimate.deviceapi.controller.manage.dto.AgentSkillResponse;
import ru.agimate.deviceapi.database.entities.AgentSkill;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.AgentSkillRepository;
import ru.agimate.deviceapi.database.repositories.SkillRepository;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSkillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AgentSkillRepository agentSkillRepository;
    private final AgentRepository agentRepository;
    private final SkillRepository skillRepository;

    public Page<AgentSkillResponse> getAgentSkills(UUID agentPubId, UUID userPubId, int page, int size) {
        verifyAgentOwnership(agentPubId, userPubId);
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        return agentSkillRepository.findByAgentPubId(agentPubId, pageRequest).map(AgentSkillResponse::from);
    }

    @Transactional
    public AgentSkillResponse create(UUID agentPubId, UUID skillPubId, UUID userPubId) {
        verifyAgentOwnership(agentPubId, userPubId);
        verifySkillOwnership(skillPubId, userPubId);

        AgentSkill agentSkill = AgentSkill.builder()
                .userPubId(userPubId)
                .agentPubId(agentPubId)
                .skillPubId(skillPubId)
                .build();

        try {
            agentSkill = agentSkillRepository.save(agentSkill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill is already bound to this agent");
        }

        log.info("Bound skill {} to agent {} for user {}", skillPubId, agentPubId, userPubId);
        return AgentSkillResponse.from(agentSkill);
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
        log.info("Unbound skill {} from agent {} for user {}", skillPubId, agentPubId, userPubId);
    }

    private void verifyAgentOwnership(UUID agentPubId, UUID userPubId) {
        var agent = agentRepository.findByPubId(agentPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        if (!agent.getUserPubId().equals(userPubId)) {
            throw new NotFoundStatusException("Agent not found");
        }
    }

    private void verifySkillOwnership(UUID skillPubId, UUID userPubId) {
        var skill = skillRepository.findByPubIdNotDeleted(skillPubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId)) {
            throw new NotFoundStatusException("Skill not found");
        }
    }
}
