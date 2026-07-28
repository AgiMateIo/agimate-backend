package ru.agimate.controlapi.util;

import lombok.experimental.UtilityClass;
import org.yaml.snakeyaml.Yaml;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@UtilityClass
public class SkillFrontmatterParser {

    private static final String FRONTMATTER_DELIMITER = "---";

    /**
     * A parsed SKILL.md: {@code name}/{@code description}/{@code connectors} from the frontmatter, and
     * {@code body} — the body without the headers (everything after the closing {@code ---}).
     */
    public record ParsedSkill(String name, String title, String description,
                              List<String> connectors, String body) {}

    /** A raw parse of a markdown document with YAML frontmatter: the fields plus the body after the closing {@code ---}. */
    public record RawFrontmatter(Map<String, Object> fields, String body) {}

    public static ParsedSkill parse(String content) {
        RawFrontmatter raw = parseRaw(content, "SKILL.md");
        Map<String, Object> frontmatter = raw.fields();

        Object nameValue = frontmatter.get("name");
        if (nameValue == null || nameValue.toString().isBlank()) {
            throw new BadRequestStatusException("SKILL.md frontmatter must contain 'name' field");
        }

        String name = nameValue.toString().strip();
        String title = frontmatter.containsKey("title")
                ? String.valueOf(frontmatter.get("title")).strip()
                : null;
        String description = frontmatter.containsKey("description")
                ? String.valueOf(frontmatter.get("description")).strip()
                : null;
        List<String> connectors = parseStringList(frontmatter.get("connectors"));

        return new ParsedSkill(name, title, description, connectors, raw.body());
    }

    /**
     * Parses a document into YAML frontmatter and a body. {@code docLabel} is the format's name for
     * error messages (e.g. {@code SKILL.md} or {@code PRESET.md}).
     */
    public static RawFrontmatter parseRaw(String content, String docLabel) {
        if (content == null || content.isBlank()) {
            throw new BadRequestStatusException(docLabel + " content is empty");
        }

        String trimmed = content.strip();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER + "\n")
                && !trimmed.startsWith(FRONTMATTER_DELIMITER + "\r\n")) {
            throw new BadRequestStatusException(docLabel + " must start with frontmatter (--- on its own line)");
        }

        int firstNewline = trimmed.indexOf('\n');
        int secondDelimiter = trimmed.indexOf("\n" + FRONTMATTER_DELIMITER, firstNewline + 1);
        if (secondDelimiter < 0) {
            throw new BadRequestStatusException(docLabel + " frontmatter is not closed (missing second ---)");
        }

        String yamlContent = trimmed.substring(firstNewline + 1, secondDelimiter).strip();
        if (yamlContent.isEmpty()) {
            throw new BadRequestStatusException(docLabel + " frontmatter is empty");
        }

        Yaml yaml = new Yaml();
        Map<String, Object> frontmatter;
        try {
            Object parsed = yaml.load(yamlContent);
            if (!(parsed instanceof Map)) {
                throw new BadRequestStatusException(docLabel + " frontmatter must be a YAML mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) parsed;
            frontmatter = cast;
        } catch (Exception e) {
            if (e instanceof BadRequestStatusException) throw e;
            throw new BadRequestStatusException("Invalid YAML in " + docLabel + " frontmatter: " + e.getMessage());
        }

        // The body is everything after the closing --- line.
        int closeLineEnd = trimmed.indexOf('\n', secondDelimiter + 1);
        String body = closeLineEnd < 0 ? "" : trimmed.substring(closeLineEnd + 1).strip();

        return new RawFrontmatter(frontmatter, body);
    }

    /** A frontmatter value as a list of strings: a YAML list or a single scalar. */
    public static List<String> parseStringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    result.add(item.toString().strip());
                }
            }
        } else if (value != null && !value.toString().isBlank()) {
            result.add(value.toString().strip());
        }
        return result;
    }
}
