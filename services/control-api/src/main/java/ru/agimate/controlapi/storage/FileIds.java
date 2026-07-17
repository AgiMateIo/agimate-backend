package ru.agimate.controlapi.storage;

import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.UUID;

/**
 * Публичный идентификатор файла: {@code agf_<uuid>} (docs/connectors/files.md). Префикс делает
 * fileId узнаваемым в параметрах тулов и логах и не конфликтует с URL/чужими id.
 */
@UtilityClass
public class FileIds {

    public static final String PREFIX = "agf_";

    public static String external(UUID id) {
        return PREFIX + id;
    }

    /** {@code Optional.empty()} — строка не является fileId (не наш префикс / не UUID). */
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
