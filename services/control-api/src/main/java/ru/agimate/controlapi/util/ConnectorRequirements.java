package ru.agimate.controlapi.util;

import lombok.experimental.UtilityClass;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.controlapi.database.model.ConnectorRequirement;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rule;
import ru.agimate.controlapi.database.model.ConnectorRequirement.Rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Shape checks and normalisation of a skill's connector requirements — shared by the frontmatter
 * parser and the JSON endpoint that replaces the list, so both inputs end up as the same stored form.
 * What needs the connector registry (does the code exist, are the params its form's non-secret
 * fields) is checked in {@code SkillService}; here only what the declaration says about itself.
 */
@UtilityClass
public class ConnectorRequirements {

    /** A key is a machine code, like a skill name: kebab-case. */
    private static final Pattern KEY_SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /**
     * The frontmatter value of {@code connectors}: a list whose items are a bare code or a mapping with
     * {@code code}, {@code key}, {@code title}, {@code params}, {@code tools}, {@code triggers}; a single
     * scalar counts as a one-item list.
     */
    public static List<ConnectorRequirement> fromYaml(Object value) {
        List<ConnectorRequirement> raw = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    raw.add(fromYamlItem(item));
                }
            }
        } else if (value != null) {
            raw.add(fromYamlItem(value));
        }
        return normalize(raw);
    }

    private static ConnectorRequirement fromYamlItem(Object item) {
        if (item instanceof Map<?, ?> map) {
            return new ConnectorRequirement(
                    text(map.get("code")),
                    text(map.get("key")),
                    text(map.get("title")),
                    stringMap(map.get("params"), "params"),
                    rules(map.get("tools"), "tools"),
                    rules(map.get("triggers"), "triggers"));
        }
        return ConnectorRequirement.of(text(item));
    }

    private static Rules rules(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new BadRequestStatusException("'" + field + "' must be a mapping with allow or deny");
        }
        List<Rule> allow = null;
        if (map.get("allow") instanceof List<?> entries) {
            allow = new ArrayList<>();
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> entryMap) {
                    allow.add(new Rule(text(entryMap.get("name")), objectMap(entryMap.get("params"))));
                } else {
                    allow.add(new Rule(text(entry), null));
                }
            }
        } else if (map.containsKey("allow")) {
            throw new BadRequestStatusException("'" + field + ".allow' must be a list");
        }
        List<String> deny = null;
        if (map.get("deny") instanceof List<?> names) {
            deny = names.stream().map(ConnectorRequirements::text).toList();
        } else if (map.containsKey("deny")) {
            throw new BadRequestStatusException("'" + field + ".deny' must be a list");
        }
        return new Rules(allow, deny);
    }

    private static Map<String, String> stringMap(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new BadRequestStatusException("'" + field + "' must be a mapping");
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((k, v) -> result.put(text(k), v == null ? null : v.toString()));
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new BadRequestStatusException("rule 'params' must be a mapping");
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString().strip();
    }

    /**
     * Fills the defaults and checks the shape: a code on every item, {@code key} defaulting to the
     * code and unique within the skill, {@code allow} xor {@code deny}, no blank names. Empty maps and
     * blanks collapse to {@code null}, so the stored form has one spelling of «nothing».
     */
    public static List<ConnectorRequirement> normalize(List<ConnectorRequirement> requirements) {
        List<ConnectorRequirement> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (ConnectorRequirement requirement : requirements) {
            String code = blankToNull(requirement.code());
            if (code == null) {
                throw new BadRequestStatusException("Every connector requirement needs a code");
            }
            String key = blankToNull(requirement.key());
            if (key == null) {
                key = code;
            } else if (!KEY_SLUG.matcher(key).matches()) {
                throw new BadRequestStatusException("Connector requirement key must be kebab-case, got: '" + key + "'");
            }
            if (!keys.add(key)) {
                throw new BadRequestStatusException("Connector requirement key '" + key + "' is declared twice");
            }
            result.add(new ConnectorRequirement(
                    code,
                    key,
                    blankToNull(requirement.title()),
                    normalizeParams(requirement.params(), key),
                    normalizeRules(requirement.tools(), key, "tools"),
                    normalizeRules(requirement.triggers(), key, "triggers")));
        }
        return result;
    }

    private static Map<String, String> normalizeParams(Map<String, String> params, String key) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        params.forEach((field, value) -> {
            if (blankToNull(field) == null || blankToNull(value) == null) {
                throw new BadRequestStatusException("Requirement '" + key + "': params must map field codes to values");
            }
            result.put(field.strip(), value.strip());
        });
        return result;
    }

    private static Rules normalizeRules(Rules rules, String key, String field) {
        if (rules == null) {
            return null;
        }
        boolean hasAllow = rules.allow() != null;
        boolean hasDeny = rules.deny() != null;
        if (hasAllow == hasDeny) {
            throw new BadRequestStatusException(
                    "Requirement '" + key + "': '" + field + "' takes exactly one of allow or deny");
        }
        Set<String> names = new HashSet<>();
        if (hasAllow) {
            List<Rule> allow = new ArrayList<>();
            for (Rule rule : rules.allow()) {
                String name = requireName(rule == null ? null : rule.name(), key, field, names);
                Map<String, Object> params = rule.params() == null || rule.params().isEmpty() ? null : rule.params();
                allow.add(new Rule(name, params));
            }
            return new Rules(allow, null);
        }
        List<String> deny = new ArrayList<>();
        for (String name : rules.deny()) {
            deny.add(requireName(name, key, field, names));
        }
        return new Rules(null, deny);
    }

    private static String requireName(String name, String key, String field, Set<String> seen) {
        String value = blankToNull(name);
        if (value == null) {
            throw new BadRequestStatusException("Requirement '" + key + "': '" + field + "' has an entry without a name");
        }
        if (!seen.add(value)) {
            throw new BadRequestStatusException(
                    "Requirement '" + key + "': '" + field + "' names '" + value + "' twice");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
