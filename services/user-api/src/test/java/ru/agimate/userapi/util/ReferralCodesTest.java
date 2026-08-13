package ru.agimate.userapi.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReferralCodes — форма реферального кода")
class ReferralCodesTest {

    @Test
    @DisplayName("восемь символов алфавита Crockford, без путающихся I, L, O и U")
    void usesCrockfordAlphabet() {
        Stream.generate(ReferralCodes::generate).limit(200).forEach(code -> {
            assertEquals(ReferralCodes.LENGTH, code.length());
            assertTrue(code.matches("^[0-9A-HJKMNP-TV-Z]+$"), "unexpected character in " + code);
        });
    }

    @Test
    @DisplayName("коды не повторяются — иначе ссылка партнёра уводила бы к соседу")
    void generatesDistinctCodes() {
        Set<String> codes = new HashSet<>();
        Stream.generate(ReferralCodes::generate).limit(1_000).forEach(codes::add);

        assertEquals(1_000, codes.size());
    }
}
