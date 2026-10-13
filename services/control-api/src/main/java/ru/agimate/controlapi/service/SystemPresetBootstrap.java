package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.enums.AgentType;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.repositories.AgentPresetRepository;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static ru.agimate.controlapi.service.SystemSkillBootstrap.SYSTEM_USER_ID;

/**
 * Seeding of the system agent role presets at application start — modelled on
 * {@link SystemSkillBootstrap}.
 *
 * <p>A preset lives as a classpath resource ({@code resources/seed/presets/<lang>/<code>/PRESET.md}),
 * every language folder seeded as its own catalogue. The frontmatter carries
 * {@code name}/{@code title}/{@code description}/{@code skills} (names of system skills) and the
 * optional {@code agentType} — the wizard asks for the type only when the preset does not declare it —
 * and the body is the blank for the agent's instructions. Seed-only-if-missing (see {@link SystemSkillBootstrap}):
 * the row is looked up by {@code (name, language)} and created only when it does not exist yet — so
 * edits through the admin API are not wiped by the next deploy. It runs after the skills are seeded
 * (see {@code @Order}) so the {@code skills} references are checked against already-seeded system
 * skills of the preset's language; an unknown name is a warning, not a refusal (resolution happens at
 * listing time anyway). The exception to seed-only-if-missing is the same one: {@link #fillTaxonomy}
 * fills an empty category or tag list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPresetBootstrap {

    static final int BOOTSTRAP_ORDER = SystemSkillBootstrap.BOOTSTRAP_ORDER + 1;

    /** Codes of the system presets — the folders in {@code seed/presets/<lang>/}, across every language. */
    static final List<String> SYSTEM_PRESET_CODES = List.of(
            "personal-assistant",
            "visual",
            "creative",
            "team-lead",
            "astrologer",
            "platform-admin",
            "home-accountant",
            "health-diary",
            "language-tutor",
            "coder",
            "investment-advisor",
            "external-agent");

    private final AgentPresetRepository agentPresetRepository;
    private final SkillRepository skillRepository;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // No enclosing transaction — as in SystemSkillBootstrap: a unique-index conflict on one preset (a race
        // between nodes on a cold start) does not poison the rest.
        for (ContentLanguage language : ContentLanguage.values()) {
            for (String code : SYSTEM_PRESET_CODES) {
                if (!SeedContentLocator.exists(SeedContentLocator.Kind.PRESET, code, language)) {
                    continue;
                }
                try {
                    seedPreset(code, language);
                } catch (Exception e) {
                    log.error("Failed to seed system preset {}/{}: {}", language.dir(), code, e.getMessage());
                }
            }
        }
    }

    private void seedPreset(String code, ContentLanguage language) {
        ParsedPreset parsed = parsePreset(SeedContentLocator.read(SeedContentLocator.Kind.PRESET, code, language));
        warnOnUnknownSkills(parsed, language);

        Optional<AgentPreset> existing = agentPresetRepository.findByNameAndLanguage(parsed.name(), language);
        if (existing.isPresent()) {
            fillTaxonomy(existing.get(), parsed);
            return;
        }

        try {
            AgentPreset preset = agentPresetRepository.save(AgentPreset.builder()
                    .name(parsed.name())
                    .language(language)
                    .title(parsed.title())
                    .description(parsed.description())
                    .instructions(parsed.instructions())
                    .skillNames(parsed.skillNames())
                    .agentType(parsed.agentType())
                    .category(parsed.category())
                    .tags(new ArrayList<>(parsed.tags()))
                    .sortOrder(parsed.sortOrder())
                    .build());
            log.info("Seeded system preset {}/{} id={} skills={}", language.dir(), preset.getName(), preset.getId(),
                    parsed.skillNames());
        } catch (DataIntegrityViolationException e) {
            // A concurrent node inserted the same (name, language) on a cold start — it is seeded already.
            log.debug("System preset {}/{} already seeded by a concurrent node", language.dir(), parsed.name());
        }
    }

    /** The same narrow exception as {@link SystemSkillBootstrap#fillTaxonomy}: fill what is empty, never overwrite. */
    private void fillTaxonomy(AgentPreset preset, ParsedPreset parsed) {
        boolean fillCategory = preset.getCategory() == ContentCategory.OTHER
                && parsed.category() != ContentCategory.OTHER;
        boolean fillTags = preset.getTags().isEmpty() && !parsed.tags().isEmpty();
        if (!fillCategory && !fillTags) {
            return;
        }
        if (fillCategory) {
            preset.setCategory(parsed.category());
        }
        if (fillTags) {
            preset.setTags(new ArrayList<>(parsed.tags()));
        }
        agentPresetRepository.save(preset);
        log.info("Filled taxonomy of system preset '{}': category={} tags={}",
                preset.getName(), preset.getCategory(), preset.getTags());
    }

    private void warnOnUnknownSkills(ParsedPreset parsed, ContentLanguage language) {
        Map<String, ?> known = skillRepository.findByNamesInLanguage(SYSTEM_USER_ID, parsed.skillNames(), language);
        for (String skillName : parsed.skillNames()) {
            if (!known.containsKey(skillName)) {
                log.warn("System preset {}/{} references unknown system skill '{}'",
                        language.dir(), parsed.name(), skillName);
            }
        }
    }

    /** A parsed PRESET.md: the frontmatter fields and the instruction body. */
    record ParsedPreset(String name, String title, String description, List<String> skillNames,
                        AgentType agentType, ContentCategory category, List<String> tags,
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
        AgentType agentType = parseAgentType(fields.get("agentType"));
        Integer sortOrder = fields.get("sortOrder") instanceof Number n ? n.intValue() : 0;

        if (raw.body().isBlank()) {
            throw new IllegalStateException("PRESET.md body (agent instructions) is empty");
        }
        return new ParsedPreset(name, title, description, skillNames, agentType,
                SkillFrontmatterParser.parseCategory(fields.get("category"), "PRESET.md"),
                SkillFrontmatterParser.parseTags(fields.get("tags"), "PRESET.md"),
                sortOrder, raw.body());
    }

    /** Absent means «the wizard asks»; a typo must not silently degrade to that. */
    private static AgentType parseAgentType(Object value) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        try {
            return AgentType.valueOf(value.toString().strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PRESET.md frontmatter has unknown agentType '" + value + "'");
        }
    }

    private static String requiredField(Map<String, Object> fields, String field) {
        Object value = fields.get(field);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException("PRESET.md frontmatter must contain '" + field + "' field");
        }
        return value.toString().strip();
    }
}
