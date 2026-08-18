package ru.agimate.userapi.service.push.rustore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.service.push.PushDelivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuStorePushTransport")
class RuStorePushTransportTest {

    private static PushProperties properties(String projectId, String serviceKey) {
        PushProperties properties = new PushProperties();
        properties.getRustore().setProjectId(projectId);
        properties.getRustore().setServiceKey(serviceKey);
        return properties;
    }

    /**
     * 404 — транспорт больше не знает токен, 400 — это вообще не токен; обе не лечатся повтором.
     * Всё остальное может починиться, и подписка должна это пережить.
     */
    @Test
    @DisplayName("404 и 400 — токен мёртв, 5xx и 429 — временный сбой")
    void statusMapping() {
        assertEquals(PushDelivery.TOKEN_GONE, RuStorePushTransport.deliveryFor(HttpStatus.NOT_FOUND));
        assertEquals(PushDelivery.TOKEN_GONE, RuStorePushTransport.deliveryFor(HttpStatus.BAD_REQUEST));
        assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor(HttpStatus.INTERNAL_SERVER_ERROR));
        assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor(HttpStatus.UNAUTHORIZED));
    }

    @Test
    @DisplayName("пустые креды — отправка выключена, старт проходит")
    void emptyCredentialsDisableSending() {
        RuStorePushTransport transport = new RuStorePushTransport(properties("", ""));

        assertDoesNotThrow(transport::checkConfiguration);
        assertFalse(transport.isConfigured());
    }

    /** Заполненная половина — опечатка, и всплыла бы она уведомлением, которое не пришло. */
    @Test
    @DisplayName("креды заполнены наполовину — отказ старта")
    void halfConfiguredFailsFast() {
        RuStorePushTransport withoutKey = new RuStorePushTransport(properties("project-1", ""));
        RuStorePushTransport withoutProject = new RuStorePushTransport(properties("", "key-1"));

        assertThrows(IllegalStateException.class, withoutKey::checkConfiguration);
        assertThrows(IllegalStateException.class, withoutProject::checkConfiguration);
    }

    @Test
    @DisplayName("обе половины на месте — транспорт готов")
    void configured() {
        RuStorePushTransport transport = new RuStorePushTransport(properties("project-1", "key-1"));

        assertDoesNotThrow(transport::checkConfiguration);
        assertTrue(transport.isConfigured());
    }
}
