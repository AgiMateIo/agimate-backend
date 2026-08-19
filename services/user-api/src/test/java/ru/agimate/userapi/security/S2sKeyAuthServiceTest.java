package ru.agimate.userapi.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.config.S2sProperties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("S2sKeyAuthService")
class S2sKeyAuthServiceTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private static S2sKeyAuthService service(String key) {
        S2sProperties properties = new S2sProperties();
        properties.setKey(key);
        S2sKeyAuthService service = new S2sKeyAuthService(properties);
        service.checkConfiguration();
        return service;
    }

    @Test
    @DisplayName("тот же секрет проходит")
    void matchingKeyPasses() {
        assertTrue(service(KEY).isValid(KEY));
    }

    @Test
    @DisplayName("другой секрет не проходит, в том числе на префиксе")
    void otherKeyRejected() {
        S2sKeyAuthService service = service(KEY);

        assertFalse(service.isValid("0123456789abcdef0123456789abcdee"));
        assertFalse(service.isValid(KEY.substring(0, 31)));
        assertFalse(service.isValid(KEY + "x"));
        assertFalse(service.isValid(null));
    }

    /** Пустой конфиг — это «внутренних вызовов на этой инсталляции нет», а не «пускать всех». */
    @Test
    @DisplayName("без ключа в конфиге не проходит никто, включая пустой заголовок")
    void emptyConfigAcceptsNobody() {
        S2sKeyAuthService service = service("");

        assertFalse(service.isValid(KEY));
        assertFalse(service.isValid(""));
    }

    /** Короткий секрет — это пароль, а не ключ; поверхность за ним не та, чтобы её подбирали. */
    @Test
    @DisplayName("слишком короткий ключ роняет старт")
    void shortKeyFailsStartup() {
        assertThrows(IllegalStateException.class, () -> service("too-short"));
        assertDoesNotThrow(() -> service(KEY));
    }
}
