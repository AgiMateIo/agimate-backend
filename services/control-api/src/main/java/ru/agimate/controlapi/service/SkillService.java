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
import ru.agimate.controlapi.controller.error.SkillConflictException;
import ru.agimate.controlapi.controller.manage.dto.*;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.repositories.SkillSpecs;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SkillRepository skillRepository;
    private final SkillFileService skillFileService;
    private final SkillConnectorService skillConnectorService;
    private final AgentRepository agentRepository;

    public Page<SkillResponse> getMySkills(UUID userId, String search, String connectorCode, int page, int size) {
        return findSkills(SkillSpecs.ownedBy(userId), search, connectorCode, page, size);
    }

    public Page<SkillResponse> getPublicSkills(UUID userId, String search, String connectorCode, int page, int size) {
        return findSkillsWithMyCopy(SkillSpecs.publicNotFeatured(), userId, search, connectorCode, page, size);
    }

    public Page<SkillResponse> getFeaturedSkills(UUID userId, String search, String connectorCode, int page, int size) {
        return findSkillsWithMyCopy(SkillSpecs.featured(), userId, search, connectorCode, page, size);
    }

    public SkillDetailResponse getSkillDetail(UUID id, UUID userId) {
        Skill skill = findAccessibleSkill(id, userId);
        String skillMd = skillFileService.readSkillMd(resolveFileOwnerId(skill));
        return SkillDetailResponse.from(skill, skillMd);
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
        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(request.skillMd());

        if (skillRepository.existsByUserIdAndNameNotDeleted(userId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(frontmatter.name())
                .description(frontmatter.description())
                .userId(userId)
                .isPublic(request.resolveIsPublic())
                .build();

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        skillFileService.saveSkillMd(skill.getId(), request.skillMd());

        log.info("Created skill '{}' id={} for user={}", skill.getName(), skill.getId(), userId);
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse update(UUID id, UUID userId, UpdateSkillRequest request) {
        Skill skill = findOwnedSkill(id, userId);
        requireNotFeaturedClone(skill);

        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(request.skillMd());

        if (!skill.getName().equals(frontmatter.name())
                && skillRepository.existsByUserIdAndNameNotDeleted(userId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        skill.setName(frontmatter.name());
        skill.setDescription(frontmatter.description());
        skill.setIsPublic(request.resolveIsPublic());
        skill.setVersion(skill.getVersion() + 1);

        try {
            skill = skillRepository.save(skill);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        skillFileService.saveSkillMd(skill.getId(), request.skillMd());

        log.info("Updated skill '{}' id={} version={}", skill.getName(), id, skill.getVersion());
        return SkillResponse.from(skill);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        Skill skill = findOwnedSkill(id, userId);
        requireNotFeaturedClone(skill);
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        skillFileService.deleteAll(skill.getId());
        log.info("Soft-deleted skill '{}' id={}", skill.getName(), id);
    }

    @Transactional
    public SkillResponse clone(UUID id, UUID userId) {
        Skill source = findAccessibleSkill(id, userId);

        if (source.getUserId().equals(userId)) {
            throw new BadRequestStatusException("Cannot clone your own skill");
        }

        skillRepository.findByUserIdAndNameNotDeleted(userId, source.getName())
                .ifPresent(existing -> {
                    throw new SkillConflictException(
                            "Skill with name '" + source.getName() + "' already exists in your collection",
                            existing.getId()
                    );
                });

        Skill clone = Skill.builder()
                .name(source.getName())
                .description(source.getDescription())
                .userId(userId)
                .parentId(source.getId())
                .isPublic(false)
                .build();

        try {
            clone = skillRepository.save(clone);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictStatusException("Skill with name '" + source.getName() + "' already exists in your collection");
        }

        if (!source.getIsFeatured()) {
            skillFileService.copyAll(source.getId(), clone.getId());
        }
        skillConnectorService.cloneBindings(source, clone);

        log.info("Cloned skill '{}' from id={} to id={} for user={}", source.getName(), source.getId(), clone.getId(), userId);
        return SkillResponse.from(clone);
    }

    @Transactional
    public void touchUpdatedAt(UUID id, UUID userId) {
        Skill skill = findOwnedSkill(id, userId);
        skillRepository.touchUpdatedAt(skill.getId(), LocalDateTime.now());
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

    public UUID resolveFileOwnerId(Skill skill) {
        if (isFeaturedClone(skill)) {
            return skill.getParentId();
        }
        return skill.getId();
    }

    public void requireNotFeaturedClone(Skill skill) {
        if (isFeaturedClone(skill)) {
            throw new ForbiddenStatusException("Cannot edit a featured skill clone");
        }
    }

    private boolean isFeaturedClone(Skill skill) {
        return skill.getParentId() != null
                && skillRepository.existsByIdAndIsFeaturedTrue(skill.getParentId());
    }

    private Page<Skill> querySkills(Specification<Skill> filter, String search, String connectorCode, int page, int size) {
        PageRequest pageRequest = buildPageRequest(page, size);
        Specification<Skill> spec = SkillSpecs.notDeleted().and(filter);
        if (search != null && !search.isBlank()) {
            spec = spec.and(SkillSpecs.searchByNameOrDescription(search));
        }
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(SkillSpecs.hasConnector(connectorCode));
        }
        return skillRepository.findAll(spec, pageRequest);
    }

    private Page<SkillResponse> findSkills(Specification<Skill> filter, String search, String connectorCode, int page, int size) {
        return querySkills(filter, search, connectorCode, page, size).map(SkillResponse::from);
    }

    private Page<SkillResponse> findSkillsWithMyCopy(Specification<Skill> filter, UUID userId, String search, String connectorCode, int page, int size) {
        Page<Skill> skills = querySkills(filter, search, connectorCode, page, size);
        Set<UUID> ids = skills.getContent().stream().map(Skill::getId).collect(Collectors.toSet());
        Map<UUID, UUID> myCopyMap = buildMyCopyMap(ids, userId);
        return skills.map(skill -> SkillResponse.from(skill, myCopyMap.get(skill.getId())));
    }

    private Map<UUID, UUID> buildMyCopyMap(Set<UUID> parentIds, UUID userId) {
        if (parentIds.isEmpty()) {
            return Map.of();
        }
        return skillRepository.findMyClonesByParentIds(parentIds, userId)
                .stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row[0],
                        row -> (UUID) row[1],
                        (existing, replacement) -> existing
                ));
    }

    private PageRequest buildPageRequest(int page, int size) {
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), Sort.by("createdAt").descending());
    }
}
