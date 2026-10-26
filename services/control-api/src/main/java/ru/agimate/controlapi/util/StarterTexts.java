package ru.agimate.controlapi.util;

import lombok.experimental.UtilityClass;
import ru.agimate.common.rest.error.BadRequestStatusException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The rules of a greeting and a starter text, the same on every way in — the seed, a skill upload, the
 * preset API, the agent's own list. A starter is sent as the user's own message by a tap, often unread:
 * keeping it one short line leaves no tail to hide an instruction in, which matters because a public
 * skill's starters are written by someone else (docs/decisions/agent-greeting.md).
 */
@UtilityClass
public class StarterTexts {

    public static final int MAX_STARTER_LENGTH = 150;
    public static final int MAX_GREETING_LENGTH = 1000;
    /** An agent's own list, and a preset's — what it hands to the agent. */
    public static final int MAX_AGENT_STARTERS = 10;
    public static final int MAX_SKILL_STARTERS = 5;

    /**
     * Stripped, blank ones dropped, order kept.
     *
     * @param label where the list came from, for the error message
     */
    public static List<String> starters(List<String> raw, int max, String label) {
        if (raw == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            String text = value == null ? "" : value.strip();
            if (text.isEmpty()) {
                continue;
            }
            if (text.contains("\n") || text.contains("\r")) {
                throw new BadRequestStatusException(label + ": a starter must be a single line: " + text);
            }
            if (text.length() > MAX_STARTER_LENGTH) {
                throw new BadRequestStatusException(label + ": a starter is longer than "
                        + MAX_STARTER_LENGTH + " characters: " + text);
            }
            if (!result.add(text)) {
                throw new BadRequestStatusException(label + ": duplicate starter: " + text);
            }
        }
        if (result.size() > max) {
            throw new BadRequestStatusException(label + ": at most " + max + " starters, got " + result.size());
        }
        return new ArrayList<>(result);
    }

    /** @return {@code null} for a blank greeting — «none», not an empty string */
    public static String greeting(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > MAX_GREETING_LENGTH) {
            throw new BadRequestStatusException(label + ": the greeting is longer than "
                    + MAX_GREETING_LENGTH + " characters");
        }
        return text;
    }
}
