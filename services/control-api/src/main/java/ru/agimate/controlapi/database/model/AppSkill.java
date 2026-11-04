package ru.agimate.controlapi.database.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.util.List;

/**
 * The skill an app declared at its last link — the JSONB document of {@code apps.skill}. It is not a
 * skill row: the row is the user's copy, refreshed from this declaration until the user edits it
 * (docs/decisions/app-skills.md).
 *
 * @param description required: a lazy skill is shown in the catalogue by it
 * @param body        the SKILL.md body without the frontmatter
 * @param language    the language of the body — metadata only; the skill row stays language-less so
 *                    its owner sees it in every catalogue
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AppSkill(String title, String description, String body, List<String> starters,
                       ContentLanguage language) {
}
