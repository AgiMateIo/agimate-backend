package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.List;
import java.util.Map;

import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

/**
 * Сидинг системных пресетов ролей агента при старте приложения — по образцу
 * {@link SystemSkillBootstrap}.
 *
 * <p>Пресет лежит как classpath-ресурс ({@code resources/seed/<lang>/presets/<code>/PRESET.md}) —
 * язык подставляет {@link SeedContentLocator}. Frontmatter — {@code name}/{@code title}/
 * {@code description}/{@code skills} (имена системных скилов), тело — заготовка инструкций агента.
 * Seed-only-if-missing (см. {@link SystemSkillBootstrap}): строка ищется по {@code name} и
 * создаётся, только если её ещё нет — правки через будущий admin UI не затираются следующим
 * деплоем. Запускается после сидинга скилов (см. {@code @Order}), чтобы ссылки {@code skills}
 * проверялись против уже засеянных системных скилов; неизвестное имя — warning, не отказ (резолв
 * всё равно происходит при листинге).
 *
 * <p>Язык, как и у скилов, фиксируется первым сидингом: {@code instructions} копируются в агента
 * при создании, поэтому существующие агенты за сменой {@code app.content.language} не идут.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPresetBootstrap {

    static final int BOOTSTRAP_ORDER = SystemSkillBootstrap.BOOTSTRAP_ORDER + 1;

    /** Коды системных пресетов — папки в {@code seed/<lang>/presets/}. */
    static final List<String> SYSTEM_PRESET_CODES = List.of(
            "personal-assistant",
            "visual",
            "creative",
            "team-lead",
            "astrologer",
            "platform-admin",
            "home-accountant",
            "health-diary");

    private final AgentPresetRepository agentPresetRepository;
    private final SkillRepository skillRepository;
    private final SeedContentLocator seedContentLocator;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // Без объемлющей транзакции — как в SystemSkillBootstrap: конфликт уникального индекса
        // на одном пресете (гонка нод на холодном старте) не отравляет остальные.
        for (String code : SYSTEM_PRESET_CODES) {
            try {
                seedPreset(code);
            } catch (Exception e) {
                log.error("Failed to seed system preset {}: {}", code, e.getMessage());
            }
        }
    }

    private void seedPreset(String code) {
        ParsedPreset parsed = parsePreset(seedContentLocator.read(SeedContentLocator.Kind.PRESET, code));
        warnOnUnknownSkills(parsed);

        if (agentPresetRepository.findByName(parsed.name()).isPresent()) {
            return;
        }

        try {
            AgentPreset preset = agentPresetRepository.save(AgentPreset.builder()
                    .name(parsed.name())
                    .title(parsed.title())
                    .description(parsed.description())
                    .instructions(parsed.instructions())
                    .skillNames(parsed.skillNames())
                    .sortOrder(parsed.sortOrder())
                    .build());
            log.info("Seeded system preset '{}' id={} skills={}", preset.getName(), preset.getId(),
                    parsed.skillNames());
        } catch (DataIntegrityViolationException e) {
            // Параллельная нода успела вставить тот же name на холодном старте — уже засеяно.
            log.debug("System preset '{}' already seeded by a concurrent node", parsed.name());
        }
    }

    private void warnOnUnknownSkills(ParsedPreset parsed) {
        for (String skillName : parsed.skillNames()) {
            if (skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, skillName).isEmpty()) {
                log.warn("System preset '{}' references unknown system skill '{}'", parsed.name(), skillName);
            }
        }
    }

    /** Разобранный PRESET.md: frontmatter-поля и тело-инструкции. */
    record ParsedPreset(String name, String title, String description, List<String> skillNames,
                        Integer sortOrder, String instructions) {}

    static ParsedPreset parsePreset(String content) {
        SkillFrontmatterParser.RawFrontmatter raw = SkillFrontmatterParser.parseRaw(content, "PRESET.md");
        Map<String, Object> fields = raw.fields();

        String name = requiredField(fields, "name");
        String title = requiredField(fields, "title");
        String description = fields.containsKey("description")
                ? String.valueOf(fields.get("description")).strip()
                : null;
        List<String> skillNames = SkillFrontmatterParser.parseStringList(fields.get("skills"));
        Integer sortOrder = fields.get("sortOrder") instanceof Number n ? n.intValue() : 0;

        if (raw.body().isBlank()) {
            throw new IllegalStateException("PRESET.md body (agent instructions) is empty");
        }
        return new ParsedPreset(name, title, description, skillNames, sortOrder, raw.body());
    }

    private static String requiredField(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("PRESET.md frontmatter must contain '" + field + "' field");
        }
        return value.toString().strip();
    }
}
