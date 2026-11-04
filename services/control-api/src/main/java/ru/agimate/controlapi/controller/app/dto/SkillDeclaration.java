package ru.agimate.controlapi.controller.app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.util.List;

/**
 * The skill an app brings at link time. The name, connectors, category and visibility are the
 * backend's to set — the app only supplies the text and the metadata of its body.
 */
@Schema(description = "The skill text the app brings; the backend turns it into a user-owned skill bound to this instance")
public record SkillDeclaration(
        @Size(max = 200)
        @Schema(description = "Human-readable skill title; defaults to the app's title, then its code")
        String title,

        @NotBlank
        @Size(max = 2000)
        @Schema(description = "Skill description — required: a lazy skill is shown in the catalogue by it")
        String description,

        @NotBlank
        @Size(max = 32768)
        @Schema(description = "SKILL.md body")
        String body,

        @Size(max = 5)
        @Schema(description = "Conversation starters; each one line, at most 150 characters")
        List<String> starters,

        @Schema(description = "Language of the body — metadata only; the skill itself stays language-less")
        ContentLanguage language
) {}
