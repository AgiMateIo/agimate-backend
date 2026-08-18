package ru.agimate.userapi.database.entities;

import java.util.Locale;
import java.util.Optional;

/**
 * The transport a push token belongs to, as the universal SDK on the device names it. Only
 * {@link #RUSTORE} is sent to today; the other two are here because the same SDK starts handing out
 * their tokens the moment a second channel is enabled in the app, and an unknown value would answer
 * 400 to a registration we do want to keep.
 *
 * <p>Mirrored by {@code chk_push_subscriptions_provider} — a new value needs a migration.
 */
public enum PushProvider {
    RUSTORE,
    FIREBASE,
    HMS;

    /** Case-insensitive: the SDK on the device names its transports in lowercase. */
    public static Optional<PushProvider> fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
