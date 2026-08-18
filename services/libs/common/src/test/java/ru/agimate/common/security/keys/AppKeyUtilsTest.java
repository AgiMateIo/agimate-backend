package ru.agimate.common.security.keys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AppKeyUtilsTest {

    @Nested
    @DisplayName("generate()")
    class GenerateTests {

        @Test
        @DisplayName("generates valid key with given prefix")
        void generate_withPrefix_createsValidKey() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");

            assertNotNull(generated.fullKey());
            assertNotNull(generated.keyId());
            assertNotNull(generated.secretHash());

            assertTrue(generated.fullKey().startsWith("dvck"));
            assertEquals(64, generated.fullKey().length());
            assertEquals(12, generated.keyId().length());
            assertEquals(64, generated.secretHash().length()); // SHA256 hex = 64 chars
        }

        @Test
        @DisplayName("generates valid key with custom prefix")
        void generate_customPrefix_createsValidKey() {
            GeneratedAppKey generated = AppKeyUtils.generate("apik");

            assertTrue(generated.fullKey().startsWith("apik"));
            assertEquals(64, generated.fullKey().length());
        }

        @Test
        @DisplayName("generated key passes parse and verify")
        void generate_keyPassesValidation() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");

            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            assertEquals("dvck", parsed.prefix());
            assertEquals(generated.keyId(), parsed.keyId());
            assertTrue(AppKeyUtils.verifyChecksum(parsed));
            assertTrue(AppKeyUtils.verifySecret(parsed.secret(), generated.secretHash()));
        }

        @Test
        @DisplayName("throws exception for invalid prefix")
        void generate_invalidPrefix_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.generate("abc")); // too short
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.generate("abcde")); // too long
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.generate("ABCD")); // uppercase
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.generate("abc1")); // contains digit
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.generate(null));
        }

        @Test
        @DisplayName("generates unique keys")
        void generate_multipleKeys_areUnique() {
            GeneratedAppKey key1 = AppKeyUtils.generate("dvck");
            GeneratedAppKey key2 = AppKeyUtils.generate("dvck");

            assertNotEquals(key1.fullKey(), key2.fullKey());
            assertNotEquals(key1.keyId(), key2.keyId());
            assertNotEquals(key1.secretHash(), key2.secretHash());
        }

        @Test
        @DisplayName("key has correct format and length")
        void generate_keyFormat_isCorrect() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");

            // Verify structure using positional parsing
            String fullKey = generated.fullKey();

            // Total length: 4 + 12 + 48 = 64
            assertEquals(64, fullKey.length());

            // Prefix (positions 0-3)
            assertEquals("dvck", fullKey.substring(0, 4));

            // keyId (positions 4-15, 12 chars)
            assertEquals(12, fullKey.substring(4, 16).length());
            assertEquals(generated.keyId(), fullKey.substring(4, 16));

            // Payload (positions 16-63, 48 chars)
            assertEquals(48, fullKey.substring(16).length());
        }
    }

    @Nested
    @DisplayName("parse()")
    class ParseTests {

        @Test
        @DisplayName("parses valid key correctly")
        void parse_validKey_returnsParsedKey() {
            GeneratedAppKey generated = AppKeyUtils.generate("apik");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            assertEquals("apik", parsed.prefix());
            assertEquals(generated.keyId(), parsed.keyId());
            assertEquals(32, parsed.secret().length);
            assertEquals(4, parsed.checksum().length);
        }

        @Test
        @DisplayName("throws exception for null or empty key")
        void parse_nullOrEmpty_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse(null));
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse(""));
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse("   "));
        }

        @Test
        @DisplayName("throws exception for invalid prefix")
        void parse_invalidPrefix_throwsException() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            // Replace first character with uppercase
            String invalidKey = "DVCK" + generated.fullKey().substring(4);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> AppKeyUtils.parse(invalidKey));
            assertTrue(ex.getMessage().contains("prefix"));
        }

        @Test
        @DisplayName("throws exception for wrong length")
        void parse_wrongLength_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse("dvckshort"));
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse("a".repeat(80)));
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse("a".repeat(63)));
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse("a".repeat(65)));
        }

        @Test
        @DisplayName("throws exception for invalid prefix format")
        void parse_invalidPrefixFormat_throwsException() {
            // Create a valid-length key but with uppercase prefix
            String invalidPrefixKey = "ABCD" + "a".repeat(12) + "a".repeat(48);
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse(invalidPrefixKey));

            // Prefix with digit
            String digitPrefix = "abc1" + "a".repeat(12) + "a".repeat(48);
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse(digitPrefix));
        }

        @Test
        @DisplayName("throws exception for invalid base64url in keyId")
        void parse_invalidKeyId_throwsException() {
            String invalidKeyId = "dvck" + "!!!!!!!!!!" + "aa" + "a".repeat(48);
            assertThrows(IllegalArgumentException.class, () -> AppKeyUtils.parse(invalidKeyId));
        }
    }

    @Nested
    @DisplayName("verifyChecksum()")
    class VerifyChecksumTests {

        @Test
        @DisplayName("returns true for valid checksum")
        void verifyChecksum_validKey_returnsTrue() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            assertTrue(AppKeyUtils.verifyChecksum(parsed));
        }

        @Test
        @DisplayName("returns false for tampered secret")
        void verifyChecksum_tamperedSecret_returnsFalse() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            // Tamper with secret
            byte[] tamperedSecret = parsed.secret().clone();
            tamperedSecret[0] ^= 0xFF;

            ParsedAppKey tampered = new ParsedAppKey(
                    parsed.prefix(),
                    parsed.keyId(),
                    tamperedSecret,
                    parsed.checksum()
            );

            assertFalse(AppKeyUtils.verifyChecksum(tampered));
        }

        @Test
        @DisplayName("returns false for tampered checksum")
        void verifyChecksum_tamperedChecksum_returnsFalse() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            // Tamper with checksum
            byte[] tamperedChecksum = parsed.checksum().clone();
            tamperedChecksum[0] ^= 0xFF;

            ParsedAppKey tampered = new ParsedAppKey(
                    parsed.prefix(),
                    parsed.keyId(),
                    parsed.secret(),
                    tamperedChecksum
            );

            assertFalse(AppKeyUtils.verifyChecksum(tampered));
        }
    }

    @Nested
    @DisplayName("verifySecret()")
    class VerifySecretTests {

        @Test
        @DisplayName("returns true for matching hash")
        void verifySecret_correctHash_returnsTrue() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            assertTrue(AppKeyUtils.verifySecret(parsed.secret(), generated.secretHash()));
        }

        @Test
        @DisplayName("returns false for wrong hash")
        void verifySecret_wrongHash_returnsFalse() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            String wrongHash = "a".repeat(64);
            assertFalse(AppKeyUtils.verifySecret(parsed.secret(), wrongHash));
        }

        @Test
        @DisplayName("returns false for null inputs")
        void verifySecret_nullInputs_returnsFalse() {
            assertFalse(AppKeyUtils.verifySecret(null, "hash"));
            assertFalse(AppKeyUtils.verifySecret(new byte[32], null));
            assertFalse(AppKeyUtils.verifySecret(null, null));
        }

        @Test
        @DisplayName("hash comparison is case insensitive")
        void verifySecret_caseInsensitive() {
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");
            ParsedAppKey parsed = AppKeyUtils.parse(generated.fullKey());

            assertTrue(AppKeyUtils.verifySecret(parsed.secret(), generated.secretHash().toUpperCase()));
        }
    }

    @Nested
    @DisplayName("hashSecret()")
    class HashSecretTests {

        @Test
        @DisplayName("produces consistent hash")
        void hashSecret_sameInput_sameOutput() {
            byte[] secret = new byte[32];
            for (int i = 0; i < 32; i++) {
                secret[i] = (byte) i;
            }

            String hash1 = AppKeyUtils.hashSecret(secret);
            String hash2 = AppKeyUtils.hashSecret(secret);

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("produces 64-character hex string")
        void hashSecret_outputFormat() {
            byte[] secret = new byte[32];
            String hash = AppKeyUtils.hashSecret(secret);

            assertEquals(64, hash.length());
            assertTrue(hash.matches("^[a-f0-9]{64}$"));
        }

        @Test
        @DisplayName("different inputs produce different hashes")
        void hashSecret_differentInputs_differentOutputs() {
            byte[] secret1 = new byte[32];
            byte[] secret2 = new byte[32];
            secret2[0] = 1;

            assertNotEquals(AppKeyUtils.hashSecret(secret1), AppKeyUtils.hashSecret(secret2));
        }
    }

    @Nested
    @DisplayName("Full workflow")
    class FullWorkflowTests {

        @Test
        @DisplayName("complete key lifecycle")
        void fullWorkflow_generateParseVerify() {
            // 1. Generate key
            GeneratedAppKey generated = AppKeyUtils.generate("dvck");

            // 2. Store keyId and secretHash in DB (simulated)
            String storedKeyId = generated.keyId();
            String storedSecretHash = generated.secretHash();

            // 3. Later, receive key from client
            String clientKey = generated.fullKey();

            // 4. Parse the key
            ParsedAppKey parsed = AppKeyUtils.parse(clientKey);

            // 5. Quick format validation via checksum
            assertTrue(AppKeyUtils.verifyChecksum(parsed));

            // 6. Lookup by keyId
            assertEquals(storedKeyId, parsed.keyId());

            // 7. Verify secret against stored hash
            assertTrue(AppKeyUtils.verifySecret(parsed.secret(), storedSecretHash));
        }

        @Test
        @DisplayName("different prefixes create different keys")
        void fullWorkflow_differentPrefixes() {
            GeneratedAppKey dvckKey = AppKeyUtils.generate("dvck");
            GeneratedAppKey apikKey = AppKeyUtils.generate("apik");
            GeneratedAppKey srvk = AppKeyUtils.generate("srvk");

            assertTrue(dvckKey.fullKey().startsWith("dvck"));
            assertTrue(apikKey.fullKey().startsWith("apik"));
            assertTrue(srvk.fullKey().startsWith("srvk"));

            // All should be valid
            assertTrue(AppKeyUtils.verifyChecksum(AppKeyUtils.parse(dvckKey.fullKey())));
            assertTrue(AppKeyUtils.verifyChecksum(AppKeyUtils.parse(apikKey.fullKey())));
            assertTrue(AppKeyUtils.verifyChecksum(AppKeyUtils.parse(srvk.fullKey())));
        }
    }
}
