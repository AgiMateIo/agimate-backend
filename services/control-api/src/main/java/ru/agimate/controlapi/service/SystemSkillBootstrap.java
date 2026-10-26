package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorBootstrap;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeding of the system skills into the database at application start.
 *
 * <p>A skill lives as a classpath resource ({@code resources/seed/skills/<lang>/<code>/SKILL.md}), and
 * every language folder is seeded — each is a catalogue of its own, so a code may exist in one
 * language only ({@link SeedContentLocator}). The owner is the synthetic {@link #SYSTEM_USER_ID}, and
 * it is published as public (so it can be bound to an agent directly, without cloning). The name,
 * description and connectors come from the frontmatter, and the body goes into {@code md_content}.
 * Seed-only-if-missing: the row is looked up by {@code (userId, name, language)} and created only when
 * it does not exist yet — after the first seeding the classpath stops being the source of truth, so
 * edits through the admin API are not wiped by the next deploy. A change to SKILL.md in the repository
 * applies only to versions not yet seeded. The one exception is {@link #fillTaxonomy}: the category, the
 * tags and the starters are metadata of the catalogue rather than content, and a skill seeded before they
 * existed would never get them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemSkillBootstrap {

    /** The synthetic owner of the system skills (there is no real user in control-api). */
    public static final java.util.UUID SYSTEM_USER_ID = new java.util.UUID(0L, 0L);

    /**
     * After the connector catalogue ({@link ru.agimate.controlapi.connectors.core.ConnectorBootstrap}),
     * which the declarations are validated against, and before presets ({@link SystemPresetBootstrap}),
     * which reference skills by name.
     */
    static final int BOOTSTRAP_ORDER = ConnectorBootstrap.BOOTSTRAP_ORDER + 1;

    /** Codes of the system skills — the folders in {@code seed/skills/<lang>/}, across every language. */
    static final List<String> SYSTEM_SKILL_CODES = List.of(
            "board",
            "time",
            "persist-memory",
            "astro",
            "divination",
            "media",
            "files",
            "platform",
            "sheets",
            "acp",
            "skill-loader",
            "tool-loader",
            "tinvest",
            "docli",
            "excalidraw",
            "subagents",
            "agents",
            "sessions",
            "web");

    private final SkillRepository skillRepository;
    private final SkillService skillService;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // No enclosing transaction: every repository call runs in its own tx, so a unique-index conflict on one
        // skill (a race between nodes on a cold start) does not poison the rest.
        for (ContentLanguage language : ContentLanguage.values()) {
            for (String code : SYSTEM_SKILL_CODES) {
                if (!SeedContentLocator.exists(SeedContentLocator.Kind.SKILL, code, language)) {
                    continue;
                }
                try {
                    seedSkill(code, language);
                } catch (Exception e) {
                    log.error("Failed to seed system skill {}/{}: {}", language.dir(), code, e.getMessage());
                }
            }
        }
    }

    private void seedSkill(String code, ContentLanguage language) {
        String content = SeedContentLocator.read(SeedContentLocator.Kind.SKILL, code, language);
        SkillFrontmatterParser.ParsedSkill parsed = SkillFrontmatterParser.parse(content);
        Optional<Skill> existing = skillRepository.findByUserIdAndNameAndLanguageNotDeleted(
                SYSTEM_USER_ID, parsed.name(), language);
        if (existing.isPresent()) {
            fillTaxonomy(existing.get(), parsed);
            return;
        }
        // The same check an upload gets: a seed file with a secret in params or a dead code is a bug to log, not to seed.
        List<ConnectorRequirement> connectors = skillService.validateConnectors(parsed.connectors());

        try {
            Skill skill = skillRepository.save(Skill.builder()
                    .name(parsed.name())
                    .language(language)
                    .title(parsed.title())
                    .description(parsed.description())
                    .mdContent(parsed.body())
                    .connectors(connectors)
                    .disclosure(parsed.disclosure())
                    .category(parsed.category())
                    .tags(new ArrayList<>(parsed.tags()))
                    .starters(new ArrayList<>(parsed.starters()))
                    .userId(SYSTEM_USER_ID)
                    .isPublic(true)
                    .build());
            log.info("Seeded system skill {}/{} id={} connectors={}", language.dir(), skill.getName(), skill.getId(),
                    skill.getConnectorCodes());
        } catch (DataIntegrityViolationException e) {
            // A concurrent node inserted the same (user_id, name, language) on a cold start — it is seeded already.
            log.debug("System skill {}/{} already seeded by a concurrent node", language.dir(), parsed.name());
        }
    }

    /**
     * The narrow exception to seed-only-if-missing: an already seeded skill keeps its body and any
     * edits, but an unset category or tag list is filled from the classpath. Only what is empty, never
     * an overwrite — a moved category stays moved. The write goes through the repository rather than
     * {@link SkillService#update}: that one bumps {@code version} and would mark the skill as needing
     * reinstall on every agent that has it.
     */
    private void fillTaxonomy(Skill skill, SkillFrontmatterParser.ParsedSkill parsed) {
        boolean fillCategory = skill.getCategory() == ContentCategory.OTHER
                && parsed.category() != ContentCategory.OTHER;
        boolean fillTags = skill.getTags().isEmpty() && !parsed.tags().isEmpty();
        boolean fillStarters = skill.getStarters().isEmpty() && !parsed.starters().isEmpty();
        if (!fillCategory && !fillTags && !fillStarters) {
            return;
        }
        if (fillCategory) {
            skill.setCategory(parsed.category());
        }
        if (fillTags) {
            skill.setTags(new ArrayList<>(parsed.tags()));
        }
        if (fillStarters) {
            skill.setStarters(new ArrayList<>(parsed.starters()));
        }
        skillRepository.save(skill);
        log.info("Filled catalogue metadata of system skill '{}': category={} tags={} starters={}",
                skill.getName(), skill.getCategory(), skill.getTags(), skill.getStarters().size());
    }
}
