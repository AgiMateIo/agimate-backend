package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Сидинг системных скиллов в БД при старте приложения.
 *
 * <p>Скилл лежит как classpath-ресурс ({@code resources/seed/<lang>/skills/<code>/SKILL.md}) —
 * язык подставляет {@link SeedContentLocator} по {@code app.content.language}. Владелец —
 * синтетический {@link #SYSTEM_USER_ID}, публикуется как public (его можно привязать к агенту
 * напрямую, без клонирования). Имя/описание/коннекторы берутся из frontmatter, тело — в
 * {@code md_content}. Seed-only-if-missing: строка ищется по {@code (userId, name)} и создаётся,
 * только если её ещё нет — после первого сидинга classpath перестаёт быть source of truth, чтобы
 * правки через будущий admin UI не затирались следующим деплоем. Изменение SKILL.md в репозитории
 * применяется только к свежим (ещё не засеянным) окружениям.
 *
 * <p><b>Язык фиксируется первым сидингом.</b> {@code name} от языка не зависит, поэтому смена
 * {@code app.content.language} на засеянном окружении не переводит существующие строки: в БД лежит
 * один набор. Переключение языка — выбор для свежей инсталляции.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemSkillBootstrap {

    /** Синтетический владелец системных скиллов (реального пользователя в control-api нет). */
    public static final java.util.UUID SYSTEM_USER_ID = new java.util.UUID(0L, 0L);

    /** Скилы сидятся раньше пресетов ({@link SystemPresetBootstrap}) — те ссылаются на них по имени. */
    static final int BOOTSTRAP_ORDER = 0;

    /** Коды системных скилов — папки в {@code seed/<lang>/skills/}. */
    static final List<String> SYSTEM_SKILL_CODES = List.of(
            "board",
            "time",
            "persist-memory",
            "astro",
            "divination",
            "media",
            "platform",
            "sheets");

    private final SkillRepository skillRepository;
    private final SeedContentLocator seedContentLocator;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // Без объемлющей транзакции: каждый вызов репозитория идёт в своей tx, поэтому конфликт
        // уникального индекса на одном скилле (гонка нод на холодном старте) не отравляет остальные.
        for (String code : SYSTEM_SKILL_CODES) {
            try {
                seedSkill(code);
            } catch (Exception e) {
                log.error("Failed to seed system skill {}: {}", code, e.getMessage());
            }
        }
    }

    private void seedSkill(String code) {
        String content = seedContentLocator.read(SeedContentLocator.Kind.SKILL, code);
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(content);
        List<String> connectors = new ArrayList<>(parsed.connectors());

        if (skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, parsed.name()).isPresent()) {
            return;
        }

        try {
            Skill skill = skillRepository.save(Skill.builder()
                    .name(parsed.name())
                    .title(parsed.title())
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
}
