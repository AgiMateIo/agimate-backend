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
import ru.agimate.controlapi.database.enums.ContentCategory;
import ru.agimate.controlapi.database.repositories.SkillRepository;
import ru.agimate.controlapi.service.seed.SeedContentLocator;
import ru.agimate.controlapi.util.SkillFrontmatterParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Seeding of the system skills into the database at application start.
 *
 * <p>A skill lives as a classpath resource ({@code resources/seed/skills/<lang>/<code>/SKILL.md}) — the
 * language is substituted by {@link SeedContentLocator} from {@code app.content.language}. The owner is
 * the synthetic {@link #SYSTEM_USER_ID}, and it is published as public (so it can be bound to an agent
 * directly, without cloning). The name, description and connectors come from the frontmatter, and the
 * body goes into {@code md_content}. Seed-only-if-missing: the row is looked up by
 * {@code (userId, name)} and created only when it does not exist yet — after the first seeding the
 * classpath stops being the source of truth, so edits through a future admin UI are not wiped by the
 * next deploy. A change to SKILL.md in the repository applies only to fresh (not yet seeded)
 * environments. The one exception is {@link #fillTaxonomy}: the category and the tags are metadata of
 * the catalogue rather than content, and a skill seeded before they existed would never get them.
 *
 * <p><b>The language is fixed by the first seeding.</b> {@code name} does not depend on the language,
 * so changing {@code app.content.language} on a seeded environment does not translate the existing
 * rows: the database holds a single set. Switching the language is a choice for a fresh installation.
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

    /** Codes of the system skills — the folders in {@code seed/skills/<lang>/}. */
    static final List<String> SYSTEM_SKILL_CODES = List.of(
            "board",
            "time",
            "persist-memory",
            "astro",
            "divination",
            "media",
            "platform",
            "sheets",
            "acp",
            "skill-loader",
            "tool-loader",
            "tinvest",
            "docli");

    private final SkillRepository skillRepository;
    private final SeedContentLocator seedContentLocator;
    private final SkillService skillService;

    @Order(BOOTSTRAP_ORDER)
    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // No enclosing transaction: every repository call runs in its own tx, so a unique-index conflict on one
        // skill (a race between nodes on a cold start) does not poison the rest.
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
        Optional<Skill> existing = skillRepository.findByUserIdAndNameNotDeleted(SYSTEM_USER_ID, parsed.name());
        if (existing.isPresent()) {
            fillTaxonomy(existing.get(), parsed);
            return;
        }
        // The same check an upload gets: a seed file with a secret in params or a dead code is a bug to log, not to seed.
        skillService.validateConnectors(parsed.connectors());

        try {
            Skill skill = skillRepository.save(Skill.builder()
                    .name(parsed.name())
                    .title(parsed.title())
                    .description(parsed.description())
                    .mdContent(parsed.body())
                    .connectors(parsed.connectors())
                    .disclosure(parsed.disclosure())
                    .category(parsed.category())
                    .tags(new ArrayList<>(parsed.tags()))
                    .userId(SYSTEM_USER_ID)
                    .isPublic(true)
                    .build());
            log.info("Seeded system skill '{}' id={} connectors={}", skill.getName(), skill.getId(), skill.getConnectorCodes());
        } catch (DataIntegrityViolationException e) {
            // A concurrent node inserted the same (user_id, name) on a cold start — it is seeded already.
            log.debug("System skill '{}' already seeded by a concurrent node", parsed.name());
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
        if (!fillCategory && !fillTags) {
            return;
        }
        if (fillCategory) {
            skill.setCategory(parsed.category());
        }
        if (fillTags) {
            skill.setTags(new ArrayList<>(parsed.tags()));
        }
        skillRepository.save(skill);
        log.info("Filled taxonomy of system skill '{}': category={} tags={}",
                skill.getName(), skill.getCategory(), skill.getTags());
    }
}
