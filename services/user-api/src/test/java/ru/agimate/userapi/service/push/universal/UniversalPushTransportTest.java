package ru.agimate.userapi.service.push.universal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.service.push.PushDelivery;
import ru.agimate.userapi.service.push.PushMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UniversalPushTransport")
class UniversalPushTransportTest {

    private static final String TOKEN = "cV8kQz1p-device-token";
    private static final String AUTH_TOKEN = "ya29.minted-for-this-send";

    /** Канал, у которого имя в теле и креды свои: ровно то, чем отличаются реальные два. */
    private static class TestTransport extends UniversalPushTransport {

        TestTransport() {
            super(new PushProperties());
        }

        @Override
        public PushProvider provider() {
            return PushProvider.FIREBASE;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        protected String wireName() {
            return "fcm";
        }

        @Override
        protected String projectId() {
            return "agimate";
        }

        @Override
        protected String authToken() {
            return AUTH_TOKEN;
        }
    }

    private final TestTransport transport = new TestTransport();

    @Nested
    @DisplayName("тело запроса")
    class Body {

        /**
         * Имя провайдера в теле — единственное, чем каналы расходятся структурно, и у firebase оно
         * не совпадает с тем, под которым лежит токен.
         */
        @Test
        @DisplayName("креды и токен едут под именем канала, а не провайдера")
        @SuppressWarnings("unchecked")
        void wireNameEverywhere() {
            Map<String, Object> body = transport.body(TOKEN, new PushMessage(Map.of("type", "webchat_message"), null), AUTH_TOKEN);

            Map<String, Object> providers = (Map<String, Object>) body.get("providers");
            assertEquals(List.of("fcm"), List.copyOf(providers.keySet()));
            Map<String, Object> fcm = (Map<String, Object>) providers.get("fcm");
            assertEquals("agimate", fcm.get("project_id"));
            assertEquals(AUTH_TOKEN, fcm.get("auth_token"));
            assertEquals(List.of(TOKEN), ((Map<String, Object>) body.get("tokens")).get("fcm"));
        }

        /** Уведомление рисует приложение — иначе оно не смогло бы промолчать над открытой перепиской. */
        @Test
        @DisplayName("только data, без notification; ttl в диалекте транспорта")
        @SuppressWarnings("unchecked")
        void dataOnly() {
            Map<String, String> data = Map.of("type", "webchat_message", "sessionId", "s-1");

            Map<String, Object> message = (Map<String, Object>) transport
                    .body(TOKEN, new PushMessage(data, Duration.ofMinutes(5)), AUTH_TOKEN)
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
                    .body(TOKEN, new PushMessage(Map.of("type", "webchat_message"), null), AUTH_TOKEN)
                    .get("message");

            assertEquals(Map.of("ttl", "3600s"), message.get("android"));
        }
    }

    @Nested
    @DisplayName("разбор отказа")
    class Refusal {

        /** Ответ агрегированный: негодные токены названы первыми шестью символами. */
        @Test
        @DisplayName("наш токен назван негодным — подписку сносим")
        void ourTokenIsNamed() {
            String body = """
                    {"code": 3, "status": "PROVIDER_ERROR", "errors": ["rustore: invalid tokens: cV8kQz, aB3dEf"]}""";

            assertEquals(PushDelivery.TOKEN_GONE, UniversalPushTransport.deliveryFor(body, TOKEN));
        }

        @Test
        @DisplayName("другие слова вендора про мёртвый токен читаются так же")
        void otherWordingsForADeadToken() {
            String unregistered = """
                    {"errors": ["fcm: cV8kQz UNREGISTERED"]}""";
            String notFound = """
                    {"errors": ["fcm: token cV8kQz — NOT_FOUND"]}""";

            assertEquals(PushDelivery.TOKEN_GONE, UniversalPushTransport.deliveryFor(unregistered, TOKEN));
            assertEquals(PushDelivery.TOKEN_GONE, UniversalPushTransport.deliveryFor(notFound, TOKEN));
        }

        /** Отказ про чужой токен — не повод сносить живое устройство. */
        @Test
        @DisplayName("назван чужой токен — подписка остаётся")
        void anotherTokenIsNamed() {
            String body = """
                    {"code": 3, "status": "PROVIDER_ERROR", "errors": ["rustore: invalid tokens: aB3dEf"]}""";

            assertEquals(PushDelivery.FAILED, UniversalPushTransport.deliveryFor(body, TOKEN));
        }

        /**
         * Одного префикса мало: вендор возвращает эхо запроса и в отказах, к токену отношения не
         * имеющих. Протухший access-токен FCM выглядел бы так же, а стоил бы устройству суток
         * молчания — памятку о подтверждённой регистрации приложение держит 24 часа.
         */
        @Test
        @DisplayName("наш токен назван, но ошибка не про токен — подписка остаётся")
        void ourTokenNamedInAnotherKindOfError() {
            String body = """
                    {"status": "AUTH_ERROR", "errors": ["fcm: invalid auth_token, request tokens: [cV8kQz]"]}""";

            assertEquals(PushDelivery.FAILED, UniversalPushTransport.deliveryFor(body, TOKEN));
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

            assertEquals(PushDelivery.FAILED, UniversalPushTransport.deliveryFor(body, TOKEN));
        }

        /**
         * Причина сноса обязана попасть в лог: без неё «токен негодный» и «стенд смотрит в чужой
         * проект» выглядят одинаково. Но токен целиком в лог не уезжает и отсюда.
         */
        @Test
        @DisplayName("ответ уходит в лог с замаскированным токеном")
        void refusalIsLoggedWithoutTheToken() {
            String body = "{\"status\": \"PROVIDER_ERROR\", \"errors\": [\"rustore: invalid token " + TOKEN + "\"]}";

            String logged = UniversalPushTransport.refusal(body, TOKEN, AUTH_TOKEN);

            assertFalse(logged.contains(TOKEN));
            assertTrue(logged.contains("PROVIDER_ERROR"));
            assertTrue(logged.contains("cV8kQz1p…"));
            assertEquals("<no body>", UniversalPushTransport.refusal("", TOKEN, AUTH_TOKEN));
        }

        /**
         * Кред в теле запроса едет всегда, и ответ вендора вправе процитировать запрос. Токен
         * устройства — право уведомлять один телефон, кред — право уведомлять все телефоны
         * установки, а у RuStore он ещё и не истекает.
         */
        @Test
        @DisplayName("кред запроса в лог не попадает, даже если вендор вернул его эхом")
        void credentialsNeverReachTheLog() {
            String body = "{\"status\": \"AUTH_ERROR\", \"errors\": [\"fcm: token " + AUTH_TOKEN + " rejected\"]}";

            String logged = UniversalPushTransport.refusal(body, TOKEN, AUTH_TOKEN);

            assertFalse(logged.contains(AUTH_TOKEN));
            assertTrue(logged.contains("<credentials>"));
            assertTrue(logged.contains("AUTH_ERROR"));
        }

        @Test
        @DisplayName("нечитаемый или пустой ответ — временный сбой")
        void unreadableAnswerIsAFailure() {
            assertEquals(PushDelivery.FAILED, UniversalPushTransport.deliveryFor("<html>502</html>", TOKEN));
            assertEquals(PushDelivery.FAILED, UniversalPushTransport.deliveryFor("", TOKEN));
        }
    }
}
