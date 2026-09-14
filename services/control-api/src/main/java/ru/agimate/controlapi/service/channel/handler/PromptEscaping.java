package ru.agimate.controlapi.service.channel.handler;

import lombok.experimental.UtilityClass;

import java.util.regex.Pattern;

/**
 * Escaping of values a channel places inside its own tags in the model's prompt: whatever a value
 * contains, it cannot close the tag around it or open a new one, and it cannot hide text from the
 * person reading the prompt while the model still reads it.
 */
@UtilityClass
public class PromptEscaping {

    /** Control and format characters (bidi overrides, zero-width joiners, line/paragraph separators), except tab and newline. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\u2028\\u2029&&[^\\t\\n]]");

    public static String text(String value) {
        if (value == null) {
            return "";
        }
        return INVISIBLE.matcher(value).replaceAll("")
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public static String attribute(String value) {
        return text(value).replace("\"", "&quot;").replace('\n', ' ').replace('\t', ' ');
    }
}
