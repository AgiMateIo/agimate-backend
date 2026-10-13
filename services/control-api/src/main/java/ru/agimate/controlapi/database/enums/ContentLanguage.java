package ru.agimate.controlapi.database.enums;

/**
 * Language of the system content — the presets and skills handed to the user, and the texts the
 * platform puts into an agent's prompt ({@code app.content.language}). Not the language of the agent's
 * answers: that follows the user's language and is set in the instructions themselves.
 *
 * <p>Each value corresponds to a folder inside every kind of seed content —
 * {@code resources/seed/<presets|skills|texts>/<lang>/} (see {@link ru.agimate.controlapi.service.seed.SeedContentLocator}), so adding a
 * language = a new constant plus a directory under each kind.
 */
public enum ContentLanguage {

    EN,
    RU;

    /** The source language — the one the code's texts are written in, and the fallback of the text bundles. */
    public static final ContentLanguage DEFAULT = EN;

    /** The language segment of a seed path — the folder inside a kind, {@code seed/<kind>/<lang>/}. */
    public String dir() {
        return name().toLowerCase();
    }
}
