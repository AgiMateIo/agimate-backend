package ru.agimate.userapi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.config.InternalApiProperties;
import ru.agimate.common.security.keys.AppKeyUtils;
import ru.agimate.common.security.keys.GeneratedAppKey;
import ru.agimate.common.security.keys.ParsedAuthkey;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InternalKeyAuthService")
class InternalKeyAuthServiceTest {

    private static InternalKeyAuthService service(String authkey) {
        InternalApiProperties properties = new InternalApiProperties();
        properties.setAuthkey(authkey);
        InternalKeyAuthService service = new InternalKeyAuthService(properties);
        service.init();
        return service;
    }

    private static GeneratedAppKey generate(String prefix) {
        return AppKeyUtils.generate(prefix);
    }

    private static String authkeyOf(String prefix, GeneratedAppKey generated) {
        return ParsedAuthkey.build(prefix, generated);
    }

    @Test
    @DisplayName("полный ключ, чей хэш лежит в конфиге, проходит")
    void validKeyPasses() {
        GeneratedAppKey generated = generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);

        assertTrue(service(authkeyOf(InternalKeyAuthService.INTERNAL_KEY_PREFIX, generated))
                .isValid(generated.fullKey()));
    }

    /** Ключ настоящий, но чужого типа — пускать его сюда значит стереть границу поверхностей. */
    @Test
    @DisplayName("ключ воркера на внутреннюю поверхность не пускают")
    void foreignPrefixRejected() {
        GeneratedAppKey internal = generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);
        GeneratedAppKey worker = generate("wrkp");

        assertFalse(service(authkeyOf(InternalKeyAuthService.INTERNAL_KEY_PREFIX, internal))
                .isValid(worker.fullKey()));
    }

    @Test
    @DisplayName("другой ключ того же типа не проходит")
    void otherKeyRejected() {
        GeneratedAppKey configured = generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);
        GeneratedAppKey other = generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);

        assertFalse(service(authkeyOf(InternalKeyAuthService.INTERNAL_KEY_PREFIX, configured))
                .isValid(other.fullKey()));
    }

    /** Пустой конфиг — это «внутренних вызовов на этой инсталляции нет», а не «пускать всех». */
    @Test
    @DisplayName("без ключа в конфиге не проходит никто")
    void emptyConfigAcceptsNobody() {
        GeneratedAppKey generated = generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);

        assertFalse(service("").isValid(generated.fullKey()));
        assertFalse(service("").isValid(null));
    }

    @Test
    @DisplayName("мусор вместо ключа не проходит")
    void malformedKeyRejected() {
        GeneratedAppKey generated = generate(InternalKeyAuthService.INTERNAL_KEY_PREFIX);
        InternalKeyAuthService service = service(authkeyOf(InternalKeyAuthService.INTERNAL_KEY_PREFIX, generated));

        assertFalse(service.isValid("not-a-key"));
        assertFalse(service.isValid(generated.fullKey().substring(0, 60) + "aaaa"));
    }

    /** Опечатка в конфиге должна ронять старт, а не первый вызов: иначе это выглядит как сеть. */
    @Test
    @DisplayName("authkey чужого типа в конфиге роняет старт")
    void wrongPrefixInConfigFailsStartup() {
        GeneratedAppKey worker = generate("wrkp");

        assertThrows(IllegalStateException.class, () -> service(authkeyOf("wrkp", worker)));
    }

    @Test
    @DisplayName("испорченный authkey в конфиге роняет старт")
    void malformedConfigFailsStartup() {
        assertThrows(IllegalArgumentException.class, () -> service("too-short"));
    }
}
