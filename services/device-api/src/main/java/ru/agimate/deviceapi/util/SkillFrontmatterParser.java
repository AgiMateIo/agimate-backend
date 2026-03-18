package ru.agimate.deviceapi.util;

import lombok.experimental.UtilityClass;
import org.yaml.snakeyaml.Yaml;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.Map;

@UtilityClass
public class SkillFrontmatterParser {

    private static final String FRONTMATTER_DELIMITER = "---";

    public record Frontmatter(String name, String description) {}

    public static Frontmatter parse(String content) {
        if (content == null || content.isBlank()) {
            throw new BadRequestStatusException("SKILL.md content is empty");
        }

        String trimmed = content.strip();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER + "\n")
                && !trimmed.startsWith(FRONTMATTER_DELIMITER + "\r\n")) {
            throw new BadRequestStatusException("SKILL.md must start with frontmatter (--- on its own line)");
        }

        int firstNewline = trimmed.indexOf('\n');
        int secondDelimiter = trimmed.indexOf("\n" + FRONTMATTER_DELIMITER, firstNewline);
        if (secondDelimiter < 0) {
            throw new BadRequestStatusException("SKILL.md frontmatter is not closed (missing second ---)");
        }

        String yamlContent = trimmed.substring(firstNewline + 1, secondDelimiter).strip();
        if (yamlContent.isEmpty()) {
            throw new BadRequestStatusException("SKILL.md frontmatter is empty");
        }

        Yaml yaml = new Yaml();
        Map<String, Object> frontmatter;
        try {
            Object parsed = yaml.load(yamlContent);
            if (!(parsed instanceof Map)) {
                throw new BadRequestStatusException("SKILL.md frontmatter must be a YAML mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) parsed;
            frontmatter = cast;
        } catch (Exception e) {
            if (e instanceof BadRequestStatusException) throw e;
            throw new BadRequestStatusException("Invalid YAML in SKILL.md frontmatter: " + e.getMessage());
        }

        Object nameValue = frontmatter.get("name");
        if (nameValue == null || nameValue.toString().isBlank()) {
            throw new BadRequestStatusException("SKILL.md frontmatter must contain 'name' field");
        }

        String name = nameValue.toString().strip();
        String description = frontmatter.containsKey("description")
                ? String.valueOf(frontmatter.get("description")).strip()
                : null;

        return new Frontmatter(name, description);
    }
}
