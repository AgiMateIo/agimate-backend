package ru.agimate.deviceapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyUtilsTest {

    @Nested
    @DisplayName("generate()")
    class GenerateTests {

        @Test
        @DisplayName("generates valid key with given prefix")
        void generate_withPrefix_createsValidKey() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");

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
            GeneratedApiKey generated = ApiKeyUtils.generate("apik");

            assertTrue(generated.fullKey().startsWith("apik"));
            assertEquals(64, generated.fullKey().length());
        }

        @Test
        @DisplayName("generated key passes parse and verify")
        void generate_keyPassesValidation() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");

            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            assertEquals("dvck", parsed.prefix());
            assertEquals(generated.keyId(), parsed.keyId());
            assertTrue(ApiKeyUtils.verifyChecksum(parsed));
            assertTrue(ApiKeyUtils.verifySecret(parsed.secret(), generated.secretHash()));
        }

        @Test
        @DisplayName("throws exception for invalid prefix")
        void generate_invalidPrefix_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.generate("abc")); // too short
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.generate("abcde")); // too long
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.generate("ABCD")); // uppercase
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.generate("abc1")); // contains digit
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.generate(null));
        }

        @Test
        @DisplayName("generates unique keys")
        void generate_multipleKeys_areUnique() {
            GeneratedApiKey key1 = ApiKeyUtils.generate("dvck");
            GeneratedApiKey key2 = ApiKeyUtils.generate("dvck");

            assertNotEquals(key1.fullKey(), key2.fullKey());
            assertNotEquals(key1.keyId(), key2.keyId());
            assertNotEquals(key1.secretHash(), key2.secretHash());
        }

        @Test
        @DisplayName("key has correct format and length")
        void generate_keyFormat_isCorrect() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");

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
            GeneratedApiKey generated = ApiKeyUtils.generate("apik");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            assertEquals("apik", parsed.prefix());
            assertEquals(generated.keyId(), parsed.keyId());
            assertEquals(32, parsed.secret().length);
            assertEquals(4, parsed.checksum().length);
        }

        @Test
        @DisplayName("throws exception for null or empty key")
        void parse_nullOrEmpty_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse(null));
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse(""));
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse("   "));
        }

        @Test
        @DisplayName("throws exception for invalid prefix")
        void parse_invalidPrefix_throwsException() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            // Replace first character with uppercase
            String invalidKey = "DVCK" + generated.fullKey().substring(4);

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> ApiKeyUtils.parse(invalidKey));
            assertTrue(ex.getMessage().contains("prefix"));
        }

        @Test
        @DisplayName("throws exception for wrong length")
        void parse_wrongLength_throwsException() {
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse("dvckshort"));
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse("a".repeat(80)));
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse("a".repeat(63)));
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse("a".repeat(65)));
        }

        @Test
        @DisplayName("throws exception for invalid prefix format")
        void parse_invalidPrefixFormat_throwsException() {
            // Create a valid-length key but with uppercase prefix
            String invalidPrefixKey = "ABCD" + "a".repeat(12) + "a".repeat(48);
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse(invalidPrefixKey));

            // Prefix with digit
            String digitPrefix = "abc1" + "a".repeat(12) + "a".repeat(48);
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse(digitPrefix));
        }

        @Test
        @DisplayName("throws exception for invalid base64url in keyId")
        void parse_invalidKeyId_throwsException() {
            String invalidKeyId = "dvck" + "!!!!!!!!!!" + "aa" + "a".repeat(48);
            assertThrows(IllegalArgumentException.class, () -> ApiKeyUtils.parse(invalidKeyId));
        }
    }

    @Nested
    @DisplayName("verifyChecksum()")
    class VerifyChecksumTests {

        @Test
        @DisplayName("returns true for valid checksum")
        void verifyChecksum_validKey_returnsTrue() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            assertTrue(ApiKeyUtils.verifyChecksum(parsed));
        }

        @Test
        @DisplayName("returns false for tampered secret")
        void verifyChecksum_tamperedSecret_returnsFalse() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            // Tamper with secret
            byte[] tamperedSecret = parsed.secret().clone();
            tamperedSecret[0] ^= 0xFF;

            ParsedApiKey tampered = new ParsedApiKey(
                    parsed.prefix(),
                    parsed.keyId(),
                    tamperedSecret,
                    parsed.checksum()
            );

            assertFalse(ApiKeyUtils.verifyChecksum(tampered));
        }

        @Test
        @DisplayName("returns false for tampered checksum")
        void verifyChecksum_tamperedChecksum_returnsFalse() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            // Tamper with checksum
            byte[] tamperedChecksum = parsed.checksum().clone();
            tamperedChecksum[0] ^= 0xFF;

            ParsedApiKey tampered = new ParsedApiKey(
                    parsed.prefix(),
                    parsed.keyId(),
                    parsed.secret(),
                    tamperedChecksum
            );

            assertFalse(ApiKeyUtils.verifyChecksum(tampered));
        }
    }

    @Nested
    @DisplayName("verifySecret()")
    class VerifySecretTests {

        @Test
        @DisplayName("returns true for matching hash")
        void verifySecret_correctHash_returnsTrue() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            assertTrue(ApiKeyUtils.verifySecret(parsed.secret(), generated.secretHash()));
        }

        @Test
        @DisplayName("returns false for wrong hash")
        void verifySecret_wrongHash_returnsFalse() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            String wrongHash = "a".repeat(64);
            assertFalse(ApiKeyUtils.verifySecret(parsed.secret(), wrongHash));
        }

        @Test
        @DisplayName("returns false for null inputs")
        void verifySecret_nullInputs_returnsFalse() {
            assertFalse(ApiKeyUtils.verifySecret(null, "hash"));
            assertFalse(ApiKeyUtils.verifySecret(new byte[32], null));
            assertFalse(ApiKeyUtils.verifySecret(null, null));
        }

        @Test
        @DisplayName("hash comparison is case insensitive")
        void verifySecret_caseInsensitive() {
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");
            ParsedApiKey parsed = ApiKeyUtils.parse(generated.fullKey());

            assertTrue(ApiKeyUtils.verifySecret(parsed.secret(), generated.secretHash().toUpperCase()));
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

            String hash1 = ApiKeyUtils.hashSecret(secret);
            String hash2 = ApiKeyUtils.hashSecret(secret);

            assertEquals(hash1, hash2);
        }

        @Test
        @DisplayName("produces 64-character hex string")
        void hashSecret_outputFormat() {
            byte[] secret = new byte[32];
            String hash = ApiKeyUtils.hashSecret(secret);

            assertEquals(64, hash.length());
            assertTrue(hash.matches("^[a-f0-9]{64}$"));
        }

        @Test
        @DisplayName("different inputs produce different hashes")
        void hashSecret_differentInputs_differentOutputs() {
            byte[] secret1 = new byte[32];
            byte[] secret2 = new byte[32];
            secret2[0] = 1;

            assertNotEquals(ApiKeyUtils.hashSecret(secret1), ApiKeyUtils.hashSecret(secret2));
        }
    }

    @Nested
    @DisplayName("Full workflow")
    class FullWorkflowTests {

        @Test
        @DisplayName("complete key lifecycle")
        void fullWorkflow_generateParseVerify() {
            // 1. Generate key
            GeneratedApiKey generated = ApiKeyUtils.generate("dvck");

            // 2. Store keyId and secretHash in DB (simulated)
            String storedKeyId = generated.keyId();
            String storedSecretHash = generated.secretHash();

            // 3. Later, receive key from client
            String clientKey = generated.fullKey();

            // 4. Parse the key
            ParsedApiKey parsed = ApiKeyUtils.parse(clientKey);

            // 5. Quick format validation via checksum
            assertTrue(ApiKeyUtils.verifyChecksum(parsed));

            // 6. Lookup by keyId
            assertEquals(storedKeyId, parsed.keyId());

            // 7. Verify secret against stored hash
            assertTrue(ApiKeyUtils.verifySecret(parsed.secret(), storedSecretHash));
        }

        @Test
        @DisplayName("different prefixes create different keys")
        void fullWorkflow_differentPrefixes() {
            GeneratedApiKey dvckKey = ApiKeyUtils.generate("dvck");
            GeneratedApiKey apikKey = ApiKeyUtils.generate("apik");
            GeneratedApiKey srvk = ApiKeyUtils.generate("srvk");

            assertTrue(dvckKey.fullKey().startsWith("dvck"));
            assertTrue(apikKey.fullKey().startsWith("apik"));
            assertTrue(srvk.fullKey().startsWith("srvk"));

            // All should be valid
            assertTrue(ApiKeyUtils.verifyChecksum(ApiKeyUtils.parse(dvckKey.fullKey())));
            assertTrue(ApiKeyUtils.verifyChecksum(ApiKeyUtils.parse(apikKey.fullKey())));
            assertTrue(ApiKeyUtils.verifyChecksum(ApiKeyUtils.parse(srvk.fullKey())));
        }
    }
}
