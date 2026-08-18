package ru.agimate.userapi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.GeneratedAppKey;
import ru.agimate.common.security.keys.ParsedAuthkey;

/**
 * Manual generator for the internal service key. Disabled by default.
 * <p>
 * Run: {@code ./gradlew :user-api:test --tests "*InternalAuthkeyGeneratorTest" -Dgenerate.internal.authkey=true}
 */
class InternalAuthkeyGeneratorTest {

    @Test
    @DisplayName("generate internal service authkey")
    @EnabledIfSystemProperty(named = "generate.internal.authkey", matches = "true")
    void generate() {
        GeneratedAppKey generated = AppKeyUtils.generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);
        String authkey = ParsedAuthkey.build(InternalKeyAuthService.INTERNAL_KEY_PREFIX, generated);

        System.out.println();
        System.out.println("=== Internal Service Authkey Generated ===");
        System.out.println("Full key (control-api, APP_NOTIFICATIONS_AUTH_TOKEN): " + generated.fullKey());
        System.out.println("Authkey  (user-api, APP_INTERNAL_AUTHKEY):            " + authkey);
        System.out.println("==========================================");
    }
}
