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
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

/**
 * Сидинг системных пресетов ролей агента при старте приложения — по образцу
 * {@link SystemSkillBootstrap}.
 *
 * <p>Пресет лежит как classpath-ресурс ({@code resources/presets/<code>/PRESET.md}): frontmatter —
 * {@code code}/{@code name}/{@code description}/{@code skills} (имена системных скилов), тело —
 * заготовка инструкций агента. Операция идемпотентна по {@code code}; при изменении контента поля
 * перезаписываются. Запускается после сидинга скилов (см. {@code @Order}), чтобы ссылки
 * {@code skills} проверялись против уже засеянных системных скилов; неизвестное имя — warning,
 * не отказ (резолв всё равно происходит при листинге).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPresetBootstrap {

    static final int BOOTSTRAP_ORDER = SystemSkillBootstrap.BOOTSTRAP_ORDER + 1;

    private static final List<String> SYSTEM_PRESET_RESOURCES = List.of(
            "presets/personal-assistant/PRESET.md");

    private final AgentPresetRepository agentPresetRepository;
    private final SkillRepository skillRepository;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // Без объемлющей транзакции — как в SystemSkillBootstrap: конфликт уникального индекса
        // на одном пресете (гонка нод на холодном старте) не отравляет остальные.
        for (String resource : SYSTEM_PRESET_RESOURCES) {
            try {
                seedPreset(resource);
            } catch (Exception e) {
                log.error("Failed to seed system preset {}: {}", resource, e.getMessage());
            }
        }
    }

    private void seedPreset(String resourcePath) {
        ParsedPreset parsed = parsePreset(readResource(resourcePath));
        warnOnUnknownSkills(parsed);

        Optional<AgentPreset> existing = agentPresetRepository.findByCode(parsed.code());
        if (existing.isPresent()) {
            updateIfChanged(existing.get(), parsed);
            return;
        }

        try {
            AgentPreset preset = agentPresetRepository.save(AgentPreset.builder()
                    .code(parsed.code())
                    .name(parsed.name())
                    .description(parsed.description())
                    .instructions(parsed.instructions())
                    .skillNames(parsed.skillNames())
                    .sortOrder(parsed.sortOrder())
                    .build());
            log.info("Seeded system preset '{}' id={} skills={}", preset.getCode(), preset.getId(),
                    parsed.skillNames());
        } catch (DataIntegrityViolationException e) {
            // Параллельная нода успела вставить тот же code — перечитываем и досинкиваем контент.
            agentPresetRepository.findByCode(parsed.code()).ifPresent(p -> updateIfChanged(p, parsed));
        }
    }

    private void updateIfChanged(AgentPreset preset, ParsedPreset parsed) {
        boolean changed = !parsed.name().equals(preset.getName())
                || !java.util.Objects.equals(parsed.description(), preset.getDescription())
                || !parsed.instructions().equals(preset.getInstructions())
                || !parsed.skillNames().equals(preset.getSkillNames())
                || !parsed.sortOrder().equals(preset.getSortOrder());
        if (!changed) {
            return;
        }
        preset.setName(parsed.name());
        preset.setDescription(parsed.description());
        preset.setInstructions(parsed.instructions());
        preset.setSkillNames(parsed.skillNames());
        preset.setSortOrder(parsed.sortOrder());
        agentPresetRepository.save(preset);
        log.info("Updated system preset '{}' id={}", preset.getCode(), preset.getId());
    }

    private void warnOnUnknownSkills(ParsedPreset parsed) {
        for (String skillName : parsed.skillNames()) {
            if (skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, skillName).isEmpty()) {
                log.warn("System preset '{}' references unknown system skill '{}'", parsed.code(), skillName);
            }
        }
    }

    /** Разобранный PRESET.md: frontmatter-поля и тело-инструкции. */
    record ParsedPreset(String code, String name, String description, List<String> skillNames,
                        Integer sortOrder, String instructions) {}

    static ParsedPreset parsePreset(String content) {
        SkillFrontmatterParser.RawFrontmatter raw = SkillFrontmatterParser.parseRaw(content, "PRESET.md");
        Map<String, Object> fields = raw.fields();

        String code = requiredField(fields, "code");
        String name = requiredField(fields, "name");
        String description = fields.containsKey("description")
                ? String.valueOf(fields.get("description")).strip()
                : null;
        List<String> skillNames = SkillFrontmatterParser.parseStringList(fields.get("skills"));
        Integer sortOrder = fields.get("sortOrder") instanceof Number n ? n.intValue() : 0;

        if (raw.body().isBlank()) {
            throw new IllegalStateException("PRESET.md body (agent instructions) is empty");
        }
        return new ParsedPreset(code, name, description, skillNames, sortOrder, raw.body());
    }

    private static String requiredField(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("PRESET.md frontmatter must contain '" + field + "' field");
        }
        return value.toString().strip();
    }

    private String readResource(String path) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read system preset resource: " + path, e);
        }
    }
}
