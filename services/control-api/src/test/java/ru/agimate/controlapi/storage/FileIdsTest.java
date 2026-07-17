package ru.agimate.controlapi.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FileIds")
class FileIdsTest {

    @Test
    @DisplayName("external/parse — roundtrip")
    void roundtrip() {
        UUID id = UUID.randomUUID();
        String external = FileIds.external(id);
        assertTrue(external.startsWith("agf_"));
        assertEquals(id, FileIds.parse(external).orElseThrow());
    }

    @Test
    @DisplayName("parse отклоняет null, чужой префикс и не-UUID")
    void parseRejectsGarbage() {
        assertTrue(FileIds.parse(null).isEmpty());
        assertTrue(FileIds.parse("").isEmpty());
        assertTrue(FileIds.parse(UUID.randomUUID().toString()).isEmpty());
        assertTrue(FileIds.parse("file_" + UUID.randomUUID()).isEmpty());
        assertTrue(FileIds.parse("agf_not-a-uuid").isEmpty());
    }
}
