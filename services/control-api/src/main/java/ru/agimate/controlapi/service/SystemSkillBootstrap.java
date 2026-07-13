package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Сидинг системных скиллов в БД при старте приложения.
 *
 * <p>Скилл лежит как classpath-ресурс ({@code resources/skills/.../SKILL.md}), владелец —
 * синтетический {@link #SYSTEM_USER_ID}, публикуется как public (его можно привязать к агенту
 * напрямую, без клонирования). Имя/описание/коннекторы берутся из frontmatter, тело — в
 * {@code md_content}. Операция идемпотентна: строка ищется по {@code (userId, name)}; при изменении
 * тела или набора коннекторов поля перезаписываются и инкрементируется {@code version}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemSkillBootstrap {

    /** Синтетический владелец системных скиллов (реального пользователя в control-api нет). */
    public static final java.util.UUID SYSTEM_USER_ID = new java.util.UUID(0L, 0L);

    /** Скилы сидятся раньше пресетов ({@link SystemPresetBootstrap}) — те ссылаются на них по имени. */
    static final int BOOTSTRAP_ORDER = 0;

    private static final List<String> SYSTEM_SKILL_RESOURCES = List.of(
            "skills/board/SKILL.md",
            "skills/time/SKILL.md",
            "skills/persist-memory/SKILL.md");

    private final SkillRepository skillRepository;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // Без объемлющей транзакции: каждый вызов репозитория идёт в своей tx, поэтому конфликт
        // уникального индекса на одном скилле (гонка нод на холодном старте) не отравляет остальные.
        for (String resource : SYSTEM_SKILL_RESOURCES) {
            try {
                seedSkill(resource);
            } catch (Exception e) {
                log.error("Failed to seed system skill {}: {}", resource, e.getMessage());
            }
        }
    }

    private void seedSkill(String resourcePath) {
        String content = readResource(resourcePath);
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(content);
        List<String> connectors = new ArrayList<>(parsed.connectors());

        Optional<Skill> existing = skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, parsed.name());
        if (existing.isPresent()) {
            updateIfChanged(existing.get(), parsed, connectors);
            return;
        }

        try {
            Skill skill = skillRepository.save(Skill.builder()
                    .name(parsed.name())
                    .description(parsed.description())
                    .mdContent(parsed.body())
                    .connectorCodes(connectors)
                    .userId(SYSTEM_USER_ID)
                    .isPublic(true)
                    .build());
            log.info("Seeded system skill '{}' id={} connectors={}", skill.getName(), skill.getId(), connectors);
        } catch (DataIntegrityViolationException e) {
            // Параллельная нода успела вставить тот же (user_id, name) — перечитываем и досинкиваем тело.
            skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, parsed.name())
                    .ifPresent(s -> updateIfChanged(s, parsed, connectors));
        }
    }

    private void updateIfChanged(Skill skill, SkillFrontmatterParser.ParsedSkill parsed, List<String> connectors) {
        boolean changed = !parsed.body().equals(skill.getMdContent())
                || !connectors.equals(skill.getConnectorCodes());
        if (!changed) {
            return;
        }
        skill.setDescription(parsed.description());
        skill.setMdContent(parsed.body());
        skill.setConnectorCodes(connectors);
        skill.setVersion(skill.getVersion() + 1);
        skillRepository.save(skill);
        log.info("Updated system skill '{}' id={} to version={}", skill.getName(), skill.getId(), skill.getVersion());
    }

    private String readResource(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read system skill resource: " + path, e);
        }
    }
}
