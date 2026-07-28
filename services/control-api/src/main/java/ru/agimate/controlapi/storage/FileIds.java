package ru.agimate.controlapi.storage;

import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.UUID;

/**
 * Public identifier of a file: {@code agf_<uuid>} (docs/connectors/files.md). The prefix makes a
 * fileId recognisable in tool parameters and logs, and keeps it from colliding with URLs or foreign ids.
 */
@UtilityClass
public class FileIds {

    public static final String PREFIX = "agf_";

    public static String external(UUID id) {
        return PREFIX + id;
    }

    /** {@code Optional.empty()} — the string is not a fileId (wrong prefix or not a UUID). */
    public static Optional<UUID> parse(String value) {
        if (value == null || !value.startsWith(PREFIX)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value.substring(PREFIX.length())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
