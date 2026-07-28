package ru.agimate.controlapi.service.runcontext;

import java.util.Map;

/**
 * A prompt block within a run's context — the wire form for {@code GetRunContext}. Content with no
 * tags or wrapper: the XML tag {@code name}+{@code attrs} is applied by the worker's renderer (an
 * empty {@code name} means raw text with no tag).
 *
 * @param name      tag name (snake_case) or an empty string
 * @param source    origin: agent | team | skill | guidance | user | connector:&lt;code&gt;
 * @param content   the content
 * @param attrs     tag attributes
 * @param trusted   false (user blocks only) → the renderer wraps it as untrusted data
 * @param ephemeral true → the worker does not persist the block into session history (e.g. memory notes)
 */
public record RunBlock(
        String name,
        String source,
        String content,
        Map<String, String> attrs,
        boolean trusted,
        boolean ephemeral
) {

    public static RunBlock trusted(String name, String source, String content, Map<String, String> attrs) {
        return new RunBlock(name, source, content, attrs, true, false);
    }
}
