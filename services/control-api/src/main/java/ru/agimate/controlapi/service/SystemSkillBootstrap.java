package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import ru.agimate.controlapi.connectors.internal.board.BoardConnectorService;
import ru.agimate.controlapi.connectors.internal.persistentmemory.PersistentMemoryConnectorService;
import ru.agimate.controlapi.connectors.internal.time.TimeConnectorService;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.entities.SkillConnector;
import ru.agimate.controlapi.database.repositories.SkillConnectorRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Сидинг системных скиллов в БД + файловое хранилище при старте приложения.
 *
 * <p>Скилл лежит как classpath-ресурс ({@code resources/skills/.../SKILL.md}), владелец —
 * синтетический {@link #SYSTEM_USER_ID}, публикуется как public + featured. Операция идемпотентна:
 * строка ищется по {@code (userId, name)}; при изменении контента файл перезаписывается и
 * инкрементируется {@code version}. Привязка к коннектору — wildcard ({@code type/name = null} =
 * весь коннектор).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemSkillBootstrap {

    /** Синтетический владелец системных скиллов (реального пользователя в control-api нет). */
    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    /** Скилл из classpath-ресурса {@code resource}, привязанный целиком к коннектору {@code connectorCode}. */
    private record SystemSkill(String resource, String connectorCode) {}

    private static final List<SystemSkill> SYSTEM_SKILLS = List.of(
            new SystemSkill("skills/board/SKILL.md", BoardConnectorService.CONNECTOR_CODE),
            new SystemSkill("skills/time/SKILL.md", TimeConnectorService.CONNECTOR_CODE),
            new SystemSkill("skills/persist-memory/SKILL.md", PersistentMemoryConnectorService.CONNECTOR_CODE));

    private final SkillRepository skillRepository;
    private final SkillConnectorRepository skillConnectorRepository;
    private final SkillFileService skillFileService;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void bootstrap() {
        for (SystemSkill def : SYSTEM_SKILLS) {
            Skill skill = seedSkill(def.resource());
            bindConnector(skill, def.connectorCode());
        }
    }

    private Skill seedSkill(String resourcePath) {
        String content = readResource(resourcePath);
        SkillFrontmatterParser.Frontmatter frontmatter = SkillFrontmatterParser.parse(content);

        Optional<Skill> existing = skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, frontmatter.name());
        if (existing.isEmpty()) {
            Skill skill = skillRepository.save(Skill.builder()
                    .name(frontmatter.name())
                    .description(frontmatter.description())
                    .userId(SYSTEM_USER_ID)
                    .isPublic(true)
                    .isFeatured(true)
                    .build());
            skillFileService.saveSkillMd(skill.getId(), content);
            log.info("Seeded system skill '{}' id={}", skill.getName(), skill.getId());
            return skill;
        }

        Skill skill = existing.get();
        boolean changed = !skillFileService.skillMdExists(skill.getId())
                || !content.equals(skillFileService.readSkillMd(skill.getId()));
        if (changed) {
            skill.setDescription(frontmatter.description());
            skill.setVersion(skill.getVersion() + 1);
            skillRepository.save(skill);
            skillFileService.saveSkillMd(skill.getId(), content);
            log.info("Updated system skill '{}' id={} to version={}", skill.getName(), skill.getId(), skill.getVersion());
        }
        return skill;
    }

    private void bindConnector(Skill skill, String connectorCode) {
        boolean alreadyBound = skillConnectorRepository.findBySkillId(skill.getId()).stream()
                .anyMatch(sc -> connectorCode.equals(sc.getConnectorCode()));
        if (alreadyBound) {
            return;
        }
        skillConnectorRepository.save(SkillConnector.builder()
                .skill(skill)
                .userId(skill.getUserId())
                .connectorCode(connectorCode)
                .type(null)
                .name(null)
                .build());
        log.info("Bound connector '{}' to system skill '{}' id={}", connectorCode, skill.getName(), skill.getId());
    }

    private String readResource(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read system skill resource: " + path, e);
        }
    }
}
