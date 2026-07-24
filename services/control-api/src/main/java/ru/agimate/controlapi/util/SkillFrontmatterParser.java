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
     * Разобранный SKILL.md: {@code name}/{@code description}/{@code connectors} из frontmatter и
     * {@code body} — тело без заголовков (всё после закрывающего {@code ---}).
     */
    public record ParsedSkill(String name, String title, String description,
                              List<String> connectors, String body) {}

    /** Сырой разбор markdown-документа с YAML-frontmatter: поля + тело после закрывающего {@code ---}. */
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
     * Разбирает документ на YAML-frontmatter и тело. {@code docLabel} — имя формата для сообщений
     * об ошибках (например {@code SKILL.md} или {@code PRESET.md}).
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

        // Тело — всё после строки закрывающего ---.
        int closeLineEnd = trimmed.indexOf('\n', secondDelimiter + 1);
        String body = closeLineEnd < 0 ? "" : trimmed.substring(closeLineEnd + 1).strip();

        return new RawFrontmatter(frontmatter, body);
    }

    /** Значение frontmatter как список строк: YAML-список или одиночный скаляр. */
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
