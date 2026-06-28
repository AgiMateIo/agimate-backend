package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.ConnectorHandler;
import ru.agimate.controlapi.connectors.core.ConnectorRegistry;
import ru.agimate.controlapi.controller.manage.dto.AgentSummaryResponse;
import ru.agimate.controlapi.controller.manage.dto.CreateSkillRequest;
import ru.agimate.controlapi.controller.manage.dto.SkillDetailResponse;
import ru.agimate.controlapi.controller.manage.dto.SkillResponse;
import ru.agimate.controlapi.controller.manage.dto.UpdateSkillRequest;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SkillRepository skillRepository;
    private final AgentRepository agentRepository;
    private final ConnectorRegistry connectorRegistry;

    public Page<SkillResponse> getMySkills(UUID userId, String search, String connectorCode, int page, int size) {
        return findSkills(SkillSpecs.ownedBy(userId), search, connectorCode, page, size);
    }

    public Page<SkillResponse> getPublicSkills(String search, String connectorCode, int page, int size) {
        return findSkills(SkillSpecs.isPublic(), search, connectorCode, page, size);
    }

    public SkillDetailResponse getSkillDetail(UUID id, UUID userId) {
        Skill skill = findAccessibleSkill(id, userId);
        return SkillDetailResponse.from(skill);
    }

    public Page<AgentSummaryResponse> getSkillAgents(UUID id, UUID userId, String search, int page, int size) {
        findAccessibleSkill(id, userId);
        String normalizedSearch = (search == null || search.isBlank()) ? null : search.trim();
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("name").ascending());
        return agentRepository.findBySkillId(id, userId, normalizedSearch, pageRequest)
                .map(AgentSummaryResponse::from);
    }

    @Transactional
    public SkillResponse create(UUID userId, CreateSkillRequest request) {
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(request.skillMd());
        validateConnectorCodes(parsed.connectors());

        if (skillRepository.existsByUserIdAndNameNotDeleted(userId, parsed.name())) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(parsed.name())
                .description(parsed.description())
                .mdContent(parsed.body())
                .connectorCodes(new ArrayList<>(parsed.connectors()))
                .userId(userId)
                .isPublic(request.resolveIsPublic())
                .build();

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        log.info("Created skill '{}' id={} for user={}", skill.getName(), skill.getId(), userId);
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse update(UUID id, UUID userId, UpdateSkillRequest request) {
        Skill skill = findOwnedSkill(id, userId);

        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(request.skillMd());
        validateConnectorCodes(parsed.connectors());

        if (!skill.getName().equals(parsed.name())
                && skillRepository.existsByUserIdAndNameNotDeleted(userId, parsed.name())) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        skill.setName(parsed.name());
        skill.setDescription(parsed.description());
        skill.setMdContent(parsed.body());
        skill.setConnectorCodes(new ArrayList<>(parsed.connectors()));
        skill.setIsPublic(request.resolveIsPublic());
        skill.setVersion(skill.getVersion() + 1);

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + parsed.name() + "' already exists");
        }

        log.info("Updated skill '{}' id={} version={}", skill.getName(), id, skill.getVersion());
        return SkillResponse.from(skill);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        Skill skill = findOwnedSkill(id, userId);
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        log.info("Soft-deleted skill '{}' id={}", skill.getName(), id);
    }

    public Skill findOwnedSkill(UUID id, UUID userId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    public Skill findAccessibleSkill(UUID id, UUID userId) {
        Skill skill = skillRepository.findByIdNotDeleted(id)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserId().equals(userId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    private Page<SkillResponse> findSkills(Specification<Skill> filter, String search, String connectorCode, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
        Specification<Skill> spec = SkillSpecs.notDeleted().and(filter);
        if (search != null && !search.isBlank()) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(search));
        }
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(SkillSpecs.hasConnector(connectorCode));
        }
        return skillRepository.findAll(spec, pageRequest).map(SkillResponse::from);
    }

    /**
     * Коды коннекторов скилла должны существовать в каталоге (registry). Иначе скилл нельзя было бы
     * осмысленно привязать — при bind'е такой код всё равно пропускается ({@code AgentSkillPolicyService}),
     * поэтому отсекаем мусор на входе. Пустой список (скилл без коннекторов) допустим.
     */
    private void validateConnectorCodes(List<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        Set<String> known = connectorRegistry.getHandlers().stream()
                .map(ConnectorHandler::connectorCode)
                .collect(Collectors.toSet());
        List<String> unknown = codes.stream()
                .filter(code -> !known.contains(code))
                .distinct()
                .toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestStatusException("Unknown connector code(s): " + String.join(", ", unknown));
        }
    }
}
