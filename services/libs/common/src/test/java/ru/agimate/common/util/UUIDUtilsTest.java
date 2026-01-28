package ru.agimate.common.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class UUIDUtilsTest {

    @Test
    @DisplayName("Should generate a valid UUID")
    void shouldGenerateValidUUID() {
        UUID uuid = UUIDUtils.generateUUIDv8();

        assertNotNull(uuid);
        assertTrue(uuid.toString().length() == 36); // Standard UUID length
        assertTrue(uuid.version() == 8); // Verify it's UUIDv8
    }

    @Test
    @DisplayName("Should generate unique UUIDs")
    void shouldGenerateUniqueUUIDs() {
        Set<UUID> uuids = new HashSet<>();
        
        // Generate 100 UUIDs to test uniqueness
        for (int i = 0; i < 100; i++) {
            UUID uuid = UUIDUtils.generateUUIDv8();
            assertTrue(uuids.add(uuid), "UUID should be unique");
        }
        
        assertEquals(100, uuids.size());
    }

    @Test
    @DisplayName("Should generate time-ordered UUIDs")
    void shouldGenerateTimeOrderedUUIDs() {
        UUID first = UUIDUtils.generateUUIDv8();
        UUID second = UUIDUtils.generateUUIDv8();

        // For UUIDv8, the most significant bits contain timestamp information
        // So, the UUID generated later should have a higher timestamp part
        assertTrue(first.compareTo(second) <= 0,
            "UUIDs should be in chronological order");
    }

    @Test
    @DisplayName("Generated UUIDs should have correct version")
    void shouldHaveCorrectVersion() {
        for (int i = 0; i < 10; i++) {
            UUID uuid = UUIDUtils.generateUUIDv8();
            assertEquals(8, uuid.version(), "UUID should be version 8");
        }
    }
    
    @Test
    @DisplayName("Should properly validate generated UUIDs")
    void shouldValidateGeneratedUUIDs() {
        UUID uuid = UUIDUtils.generateUUIDv8();
        String uuidString = uuid.toString();
        
        assertTrue(UUIDUtils.validateUUIDv8(uuidString), "Generated UUID should be valid");
        assertFalse(UUIDUtils.validateUUIDv8("invalid-uuid"), "Invalid UUID string should not be valid");
        assertFalse(UUIDUtils.validateUUIDv8(null), "Null UUID should not be valid");
    }
    
    @Test
    @DisplayName("Should reject tampered UUIDs")
    void shouldRejectTamperedUUIDs() {
        UUID original = UUIDUtils.generateUUIDv8();
        String originalStr = original.toString();

        // Tamper with the UUID by changing one character in the middle
        String tampered = originalStr.substring(0, 10) + "X" + originalStr.substring(11);

        assertTrue(UUIDUtils.validateUUIDv8(originalStr), "Original UUID should be valid");
        assertFalse(UUIDUtils.validateUUIDv8(tampered), "Tampered UUID should not be valid");
    }

    @Test
    @DisplayName("Generated UUIDs should have increasing timestamps")
    void shouldHaveIncreasingTimestamps() {
        UUID first = UUIDUtils.generateUUIDv8();
        UUID second = UUIDUtils.generateUUIDv8();

        // Extract timestamp parts from most significant bits
        long firstTimestamp = (first.getMostSignificantBits() >> 16) & 0xFFFFFFFFFFFFL;
        long secondTimestamp = (second.getMostSignificantBits() >> 16) & 0xFFFFFFFFFFFFL;

        assertTrue(secondTimestamp >= firstTimestamp,
            "Later generated UUID should have equal or greater timestamp part");
    }

    @Test
    @DisplayName("Validation should work with various invalid inputs")
    void shouldHandleVariousInvalidInputs() {
        assertFalse(UUIDUtils.validateUUIDv8(""), "Empty string should not be valid");
        assertFalse(UUIDUtils.validateUUIDv8("not-a-uuid"), "Non-UUID string should not be valid");
        assertFalse(UUIDUtils.validateUUIDv8("123e4567-e89b-12d3-a456-426614174000"),
            "Valid UUIDv1 should not validate as UUIDv8");
        assertFalse(UUIDUtils.validateUUIDv8("00000000-0000-8000-0000-000000000000"),
            "Malformed UUIDv8 should not be valid if checksum is wrong");
    }
}