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

/**
 * Сидинг системных скиллов в БД при старте приложения.
 *
 * <p>Скилл лежит как classpath-ресурс ({@code resources/skills/.../SKILL.md}), владелец —
 * синтетический {@link #SYSTEM_USER_ID}, публикуется как public (его можно привязать к агенту
 * напрямую, без клонирования). Имя/описание/коннекторы берутся из frontmatter, тело — в
 * {@code md_content}. Seed-only-if-missing: строка ищется по {@code (userId, name)} и создаётся,
 * только если её ещё нет — после первого сидинга classpath перестаёт быть source of truth, чтобы
 * правки через будущий admin UI не затирались следующим деплоем. Изменение SKILL.md в репозитории
 * применяется только к свежим (ещё не засеянным) окружениям.
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

        if (skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, parsed.name()).isPresent()) {
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
            // Параллельная нода успела вставить тот же (user_id, name) на холодном старте — уже засеяно.
            log.debug("System skill '{}' already seeded by a concurrent node", parsed.name());
        }
    }

    private String readResource(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read system skill resource: " + path, e);
        }
    }
}
