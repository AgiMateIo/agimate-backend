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
 * environments.
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

    /** Skills are seeded before presets ({@link SystemPresetBootstrap}) — those reference them by name. */
    static final int BOOTSTRAP_ORDER = 0;

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
            "acp");

    private final SkillRepository skillRepository;
    private final SeedContentLocator seedContentLocator;

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
            // A concurrent node inserted the same (user_id, name) on a cold start — it is seeded already.
            log.debug("System skill '{}' already seeded by a concurrent node", parsed.name());
        }
    }
}
