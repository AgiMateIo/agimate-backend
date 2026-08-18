package ru.agimate.common.security.keys;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParsedAuthkeyTest {

    @Test
    @DisplayName("parse roundtrip from generated key")
    void parse_roundtrip() {
        GeneratedAppKey generated = AppKeyUtils.generate("wrkp");
        String authkey = ParsedAuthkey.build("wrkp", generated);
        assertEquals(80, authkey.length());

        ParsedAuthkey parsed = ParsedAuthkey.parse(authkey);
        assertEquals("wrkp", parsed.prefix());
        assertEquals(generated.keyId(), parsed.keyId());
        assertEquals(generated.secretHash(), parsed.keyHash());
    }

    @Test
    @DisplayName("rejects wrong length")
    void parse_wrongLength() {
        assertThrows(IllegalArgumentException.class, () -> ParsedAuthkey.parse("wrkp"));
        assertThrows(IllegalArgumentException.class, () -> ParsedAuthkey.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ParsedAuthkey.parse("a".repeat(79)));
        assertThrows(IllegalArgumentException.class, () -> ParsedAuthkey.parse("a".repeat(81)));
    }

    @Test
    @DisplayName("rejects invalid prefix / keyId / keyHash")
    void parse_invalidComponents() {
        GeneratedAppKey g = AppKeyUtils.generate("wrkp");
        String good = ParsedAuthkey.build("wrkp", g);

        // Uppercase prefix
        String badPrefix = "WRKP" + good.substring(4);
        assertThrows(IllegalArgumentException.class, () -> ParsedAuthkey.parse(badPrefix));

        // Invalid hex hash (non-hex char in last 64)
        String badHash = good.substring(0, 16) + ("g".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ParsedAuthkey.parse(badHash));
    }
}
