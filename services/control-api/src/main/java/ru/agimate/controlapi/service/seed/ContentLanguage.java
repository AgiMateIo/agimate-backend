package ru.agimate.controlapi.service.seed;

/**
 * Language of the system content — the presets and skills handed to the user, and the texts the
 * platform puts into an agent's prompt ({@code app.content.language}). Not the language of the agent's
 * answers: that follows the user's language and is set in the instructions themselves.
 *
 * <p>Each value corresponds to a folder {@code resources/seed/<lang>/} (see
 * {@link SeedContentLocator}), so adding a language = a new constant plus a directory.
 */
public enum ContentLanguage {

    EN,
    RU;

    /** The source language: the fallback when there is no content for the chosen one. */
    public static final ContentLanguage DEFAULT = EN;

    /** The path segment in {@code resources/seed/}. */
    public String dir() {
        return name().toLowerCase();
    }
}
