package ru.agimate.controlapi.util;

import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Naming of a new agent. A name is not a key — nothing breaks on a duplicate — but a wizard started
 * from the same preset three times leaves three identical rows in the list, and the user has no way
 * to tell them apart before opening each one.
 */
@UtilityClass
public class AgentNames {

    /** A counter this class itself has added before; stripped so «Внешний ИИ (2)» grows into (3), not (2) (2). */
    private static final Pattern COUNTER_SUFFIX = Pattern.compile("\\s*\\(\\d+\\)$");

    /**
     * {@code requested} when it is free, otherwise the first free {@code «<name> (n)»} starting at 2.
     *
     * @param taken names of the user's existing agents — the scope of uniqueness is one user
     */
    public static String unique(String requested, Collection<String> taken) {
        String base = COUNTER_SUFFIX.matcher(requested.strip()).replaceFirst("").strip();
        if (base.isEmpty()) {
            base = requested.strip();
        }
        Set<String> used = Set.copyOf(taken);
        if (!used.contains(base)) {
            return base;
        }
        // Terminates: every iteration rules out one existing name, and there are finitely many.
        for (int counter = 2; ; counter++) {
            String candidate = base + " (" + counter + ")";
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
    }
}
