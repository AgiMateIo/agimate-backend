package ru.agimate.userapi.database.entities;

import java.util.Locale;
import java.util.Optional;

/**
 * The transport a push token belongs to, as the universal SDK on the device names it.
 * {@link #RUSTORE} and {@link #FIREBASE} are both sent to, in parallel rather than one behind the
 * other (docs/decisions/push-second-channel.md) — one device leaves a row per channel. {@link #HMS}
 * is stored and skipped: the same SDK would start handing out its tokens the moment a third channel
 * is enabled in the app, and an unknown value would answer 400 to a registration we do want to keep.
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
