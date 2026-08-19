package ru.agimate.userapi.service.push.rustore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.service.push.PushDelivery;
import ru.agimate.userapi.service.push.PushMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    private static final String TOKEN = "cV8kQz1p-device-token";

    @Nested
    @DisplayName("тело запроса")
    class Body {

        private final RuStorePushTransport transport = new RuStorePushTransport(properties("project-1", "key-1"));

        @Test
        @DisplayName("креды едут в теле, токен — списком")
        @SuppressWarnings("unchecked")
        void credentialsAndTokensInTheBody() {
            Map<String, Object> body = transport.body(TOKEN, new PushMessage(Map.of("type", "webchat_message"), null));

            Map<String, Object> rustore = (Map<String, Object>) ((Map<String, Object>) body.get("providers")).get("rustore");
            assertEquals("project-1", rustore.get("project_id"));
            assertEquals("key-1", rustore.get("auth_token"));
            assertEquals(List.of(TOKEN), ((Map<String, Object>) body.get("tokens")).get("rustore"));
        }

        /** Уведомление рисует приложение — иначе оно не смогло бы промолчать над открытой перепиской. */
        @Test
        @DisplayName("только data, без notification; ttl в диалекте транспорта")
        @SuppressWarnings("unchecked")
        void dataOnly() {
            Map<String, String> data = Map.of("type", "webchat_message", "sessionId", "s-1");

            Map<String, Object> message = (Map<String, Object>) transport
                    .body(TOKEN, new PushMessage(data, Duration.ofMinutes(5)))
                    .get("message");

            assertEquals(data, message.get("data"));
            assertEquals(Map.of("ttl", "300s"), message.get("android"));
            assertFalse(message.containsKey("notification"));
        }

        /** Не сказали срок — берётся общий из конфигурации, а не дефолт транспорта в четыре недели. */
        @Test
        @DisplayName("без ttl в сообщении берётся ttl из конфигурации")
        @SuppressWarnings("unchecked")
        void fallsBackToConfiguredTtl() {
            Map<String, Object> message = (Map<String, Object>) transport
                    .body(TOKEN, new PushMessage(Map.of("type", "webchat_message"), null))
                    .get("message");

            assertEquals(Map.of("ttl", "3600s"), message.get("android"));
        }
    }

    @Nested
    @DisplayName("разбор отказа")
    class Refusal {

        /** Ответ агрегированный: негодные токены названы первыми шестью символами. */
        @Test
        @DisplayName("наш токен назван среди негодных — подписку сносим")
        void ourTokenIsNamed() {
            String body = """
                    {"code": 3, "status": "PROVIDER_ERROR", "errors": ["rustore: invalid tokens: cV8kQz, aB3dEf"]}""";

            assertEquals(PushDelivery.TOKEN_GONE, RuStorePushTransport.deliveryFor(body, TOKEN));
        }

        /** Отказ про чужой токен — не повод сносить живое устройство. */
        @Test
        @DisplayName("назван чужой токен — подписка остаётся")
        void anotherTokenIsNamed() {
            String body = """
                    {"code": 3, "status": "PROVIDER_ERROR", "errors": ["rustore: invalid tokens: aB3dEf"]}""";

            assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor(body, TOKEN));
        }

        /**
         * Кривой запрос — наша ошибка формата, а не мёртвый токен. Прежнее правило «400 = токен
         * мёртв» на этом API снесло бы подписки всех, кому запрос предназначался.
         */
        @Test
        @DisplayName("ошибка валидации запроса подписку не трогает")
        void validationErrorKeepsTheSubscription() {
            String body = """
                    {"code": 3, "status": "VALIDATION_ERROR", "errors": ["message: data must not be empty"]}""";

            assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor(body, TOKEN));
        }

        @Test
        @DisplayName("нечитаемый или пустой ответ — временный сбой")
        void unreadableAnswerIsAFailure() {
            assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor("<html>502</html>", TOKEN));
            assertEquals(PushDelivery.FAILED, RuStorePushTransport.deliveryFor("", TOKEN));
        }
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
