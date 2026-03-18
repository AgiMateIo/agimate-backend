package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.controller.manage.dto.*;
import ru.agimate.deviceapi.database.entities.Skill;
import ru.agimate.deviceapi.database.entities.SkillType;
import ru.agimate.deviceapi.database.repositories.SkillRepository;
import ru.agimate.deviceapi.util.SkillFrontmatterParser;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SkillService {

    private final SkillRepository skillRepository;
    private final SkillFileService skillFileService;

    public Page<SkillResponse> getMySkills(UUID userPubId, String search, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Skill> skills;
        if (search != null && !search.isBlank()) {
            skills = skillRepository.searchByUserPubIdNotDeleted(userPubId, search, pageRequest);
        } else {
            skills = skillRepository.findByUserPubIdNotDeleted(userPubId, pageRequest);
        }
        return skills.map(SkillResponse::from);
    }

    public Page<SkillResponse> getPublicSkills(String search, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Skill> skills;
        if (search != null && !search.isBlank()) {
            skills = skillRepository.searchPublicNotDeleted(search, pageRequest);
        } else {
            skills = skillRepository.findPublicNotDeleted(pageRequest);
        }
        return skills.map(SkillResponse::from);
    }

    public SkillDetailResponse getSkillDetail(UUID pubId, UUID userPubId) {
        Skill skill = findAccessibleSkill(pubId, userPubId);
        String skillMd = skillFileService.readSkillMd(skill.getName(), skill.getUserPubId());
        return SkillDetailResponse.from(skill, skillMd);
    }

    @Transactional
    public SkillResponse create(UUID userPubId, CreateSkillRequest request) {
        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(request.skillMd());

        if (skillRepository.existsByUserPubIdAndNameNotDeleted(userPubId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(frontmatter.name())
                .description(frontmatter.description())
                .type(request.type())
                .userPubId(userPubId)
                .isPublic(request.resolveIsPublic())
                .build();
        skill = skillRepository.save(skill);

        skillFileService.saveSkillMd(skill.getName(), userPubId, request.skillMd());

        log.info("Created skill '{}' for user={}", skill.getName(), userPubId);
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse createFromFile(UUID userPubId, String skillMdContent, SkillType type, boolean isPublic) {
        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(skillMdContent);

        if (skillRepository.existsByUserPubIdAndNameNotDeleted(userPubId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        Skill skill = Skill.builder()
                .name(frontmatter.name())
                .description(frontmatter.description())
                .type(type)
                .userPubId(userPubId)
                .isPublic(isPublic)
                .build();
        skill = skillRepository.save(skill);

        skillFileService.saveSkillMd(skill.getName(), userPubId, skillMdContent);

        log.info("Created skill '{}' from file for user={}", skill.getName(), userPubId);
        return SkillResponse.from(skill);
    }

    @Transactional
    public SkillResponse update(UUID pubId, UUID userPubId, UpdateSkillRequest request) {
        Skill skill = findOwnedSkill(pubId, userPubId);

        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(request.skillMd());

        if (!skill.getName().equals(frontmatter.name())
                && skillRepository.existsByUserPubIdAndNameNotDeleted(userPubId, frontmatter.name())) {
            throw new ConflictStatusException("Skill with name '" + frontmatter.name() + "' already exists");
        }

        String oldName = skill.getName();
        skill.setName(frontmatter.name());
        skill.setDescription(frontmatter.description());
        skill.setType(request.type());
        skill.setIsPublic(request.resolveIsPublic());
        skill.setVersion(skill.getVersion() + 1);
        skill = skillRepository.save(skill);

        if (!oldName.equals(frontmatter.name())) {
            skillFileService.copyAll(oldName, userPubId, frontmatter.name(), userPubId);
            skillFileService.deleteAll(oldName, userPubId);
        }

        skillFileService.saveSkillMd(skill.getName(), userPubId, request.skillMd());

        log.info("Updated skill '{}' pubId={} version={}", skill.getName(), pubId, skill.getVersion());
        return SkillResponse.from(skill);
    }

    @Transactional
    public void delete(UUID pubId, UUID userPubId) {
        Skill skill = findOwnedSkill(pubId, userPubId);
        skillRepository.softDelete(skill.getId(), LocalDateTime.now());
        skillFileService.deleteAll(skill.getName(), userPubId);
        log.info("Soft-deleted skill '{}' pubId={}", skill.getName(), pubId);
    }

    @Transactional
    public SkillResponse clone(UUID pubId, UUID userPubId) {
        Skill source = findAccessibleSkill(pubId, userPubId);

        if (source.getUserPubId().equals(userPubId)) {
            throw new BadRequestStatusException("Cannot clone your own skill");
        }

        if (skillRepository.existsByUserPubIdAndNameNotDeleted(userPubId, source.getName())) {
            throw new ConflictStatusException("Skill with name '" + source.getName() + "' already exists in your collection");
        }

        Skill clone = Skill.builder()
                .name(source.getName())
                .description(source.getDescription())
                .type(source.getType())
                .userPubId(userPubId)
                .isPublic(false)
                .build();
        clone = skillRepository.save(clone);

        skillFileService.copyAll(source.getName(), source.getUserPubId(), clone.getName(), userPubId);

        log.info("Cloned skill '{}' from user={} to user={}", source.getName(), source.getUserPubId(), userPubId);
        return SkillResponse.from(clone);
    }

    public Skill findOwnedSkill(UUID pubId, UUID userPubId) {
        Skill skill = skillRepository.findByPubIdNotDeleted(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId)) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }

    public Skill findAccessibleSkill(UUID pubId, UUID userPubId) {
        Skill skill = skillRepository.findByPubIdNotDeleted(pubId)
                .orElseThrow(() -> new NotFoundStatusException("Skill not found"));
        if (!skill.getUserPubId().equals(userPubId) && !skill.getIsPublic()) {
            throw new ForbiddenStatusException("Access denied");
        }
        return skill;
    }
}
