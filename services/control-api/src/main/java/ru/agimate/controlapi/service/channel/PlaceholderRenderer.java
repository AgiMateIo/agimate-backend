package ru.agimate.controlapi.service.channel;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recursively renders placeholders in a JSONB-style template:
 * - {text} → текст ответа агента
 * - {trigger.<dot.path>} → значение из payload последнего IN-сообщения сессии
 */
@UtilityClass
public class PlaceholderRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)}");

    public static final String TEXT_PLACEHOLDER = "text";
    public static final String TRIGGER_PREFIX = "trigger.";

    public static Map<String, Object> render(Map<String, Object> template,
                                             String text,
                                             Map<String, Object> triggerInput) {
        return (Map<String, Object>) renderValue(template, text, triggerInput);
    }

    @SuppressWarnings("unchecked")
    private static Object renderValue(Object value, String text, Map<String, Object> triggerInput) {
        if (value instanceof String s) {
            return renderString(s, text, triggerInput);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                rendered.put(entry.getKey().toString(), renderValue(entry.getValue(), text, triggerInput));
            }
            return rendered;
        }
        if (value instanceof List<?> list) {
            List<Object> rendered = new ArrayList<>(list.size());
            for (Object item : list) {
                rendered.add(renderValue(item, text, triggerInput));
            }
            return rendered;
        }
        return value;
    }

    private static Object renderString(String s, String text, Map<String, Object> triggerInput) {
        Matcher matcher = PLACEHOLDER.matcher(s);
        if (!matcher.find()) {
            return s;
        }
        // If the whole string IS a single placeholder, return the resolved object (preserve type)
        if (matcher.start() == 0 && matcher.end() == s.length()) {
            return resolve(matcher.group(1), text, triggerInput);
        }
        // Otherwise interpolate as string
        matcher.reset();
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            Object resolved = resolve(matcher.group(1), text, triggerInput);
            String replacement = resolved == null ? "" : resolved.toString();
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static Object resolve(String key, String text, Map<String, Object> triggerInput) {
        String trimmed = key.trim();
        if (TEXT_PLACEHOLDER.equals(trimmed)) {
            return text;
        }
        if (trimmed.startsWith(TRIGGER_PREFIX)) {
            return InputFilterEvaluator.resolvePath(triggerInput, trimmed.substring(TRIGGER_PREFIX.length()));
        }
        return null;
    }
}
