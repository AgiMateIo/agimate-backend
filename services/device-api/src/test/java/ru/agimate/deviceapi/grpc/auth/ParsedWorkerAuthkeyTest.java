package ru.agimate.deviceapi.grpc.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.deviceapi.util.AppKeyUtils;
import ru.agimate.deviceapi.util.GeneratedAppKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ParsedWorkerAuthkeyTest {

    @Test
    @DisplayName("parse roundtrip from generated key")
    void parse_roundtrip() {
        GeneratedAppKey generated = AppKeyUtils.generate("wrkp");
        String authkey = ParsedWorkerAuthkey.build("wrkp", generated);
        assertEquals(80, authkey.length());

        ParsedWorkerAuthkey parsed = ParsedWorkerAuthkey.parse(authkey);
        assertEquals("wrkp", parsed.prefix());
        assertEquals(generated.keyId(), parsed.keyId());
        assertEquals(generated.secretHash(), parsed.keyHash());
    }

    @Test
    @DisplayName("rejects wrong length")
    void parse_wrongLength() {
        assertThrows(IllegalArgumentException.class, () -> ParsedWorkerAuthkey.parse("wrkp"));
        assertThrows(IllegalArgumentException.class, () -> ParsedWorkerAuthkey.parse(null));
        assertThrows(IllegalArgumentException.class, () -> ParsedWorkerAuthkey.parse("a".repeat(79)));
        assertThrows(IllegalArgumentException.class, () -> ParsedWorkerAuthkey.parse("a".repeat(81)));
    }

    @Test
    @DisplayName("rejects invalid prefix / keyId / keyHash")
    void parse_invalidComponents() {
        GeneratedAppKey g = AppKeyUtils.generate("wrkp");
        String good = ParsedWorkerAuthkey.build("wrkp", g);

        // Uppercase prefix
        String badPrefix = "WRKP" + good.substring(4);
        assertThrows(IllegalArgumentException.class, () -> ParsedWorkerAuthkey.parse(badPrefix));

        // Invalid hex hash (non-hex char in last 64)
        String badHash = good.substring(0, 16) + ("g".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> ParsedWorkerAuthkey.parse(badHash));
    }
}
