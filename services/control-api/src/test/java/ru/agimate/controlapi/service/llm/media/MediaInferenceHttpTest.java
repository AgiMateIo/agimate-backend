package ru.agimate.controlapi.service.llm.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import ru.agimate.controlapi.service.llm.media.MediaInferenceHttp.DataUri;
import ru.agimate.controlapi.service.llm.media.MediaInferenceHttp.Usage;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaInferenceHttp — парсинг мультимодального chat/completions-ответа")
class MediaInferenceHttpTest {

    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
    private static final String PNG_DATA_URI =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);

    private static Map<String, Object> responseWithMessage(Map<String, Object> message) {
        return Map.of("choices", List.of(Map.of("message", message)));
    }

    @Nested
    @DisplayName("rejectionMessage")
    class RejectionMessage {

        private static final String BODY = "{\"error\":{\"code\":\"INTERNAL_ERROR\"}}";

        @Test
        @DisplayName("5xx: подсказка про транспорт + тело провайдера — агент не должен ретраить бесконечно")
        void serverErrorCarriesTransportHint() {
            String message = MediaInferenceHttp.rejectionMessage(HttpStatus.INTERNAL_SERVER_ERROR, BODY);

            assertTrue(message.contains("500"), message);
            assertTrue(message.contains("separate endpoint"), message);
            assertTrue(message.contains(BODY), message);
        }

        @Test
        @DisplayName("4xx: тело провайдера как есть, без домыслов про транспорт")
        void clientErrorStaysVerbatim() {
            String message = MediaInferenceHttp.rejectionMessage(HttpStatus.BAD_REQUEST, BODY);

            assertTrue(message.contains("400"), message);
            assertTrue(message.contains(BODY), message);
            assertFalse(message.contains("separate endpoint"), message);
        }
    }

    @Nested
    @DisplayName("firstImage")
    class FirstImage {

        @Test
        @DisplayName("data-URI из message.images доезжает байтами с mime")
        void extractsDataUri() {
            Map<String, Object> response = responseWithMessage(Map.of(
                    "content", "Вот картинка",
                    "images", List.of(Map.of(
                            "type", "image_url",
                            "image_url", Map.of("url", PNG_DATA_URI)))));

            DataUri image = MediaInferenceHttp.firstImage(response).orElseThrow();

            assertEquals("image/png", image.mime());
            assertArrayEquals(PNG_BYTES, image.bytes());
        }

        @Test
        @DisplayName("ответ без картинки (текстовый отказ) → empty")
        void refusalWithoutImage() {
            Map<String, Object> response = responseWithMessage(Map.of("content", "Не могу это нарисовать"));

            assertTrue(MediaInferenceHttp.firstImage(response).isEmpty());
        }

        @Test
        @DisplayName("http-ссылка вместо data-URI и битый base64 → empty, не исключение")
        void nonDataUriSkipped() {
            Map<String, Object> httpUrl = responseWithMessage(Map.of("images", List.of(
                    Map.of("image_url", Map.of("url", "https://cdn.example.com/img.png")))));
            Map<String, Object> badBase64 = responseWithMessage(Map.of("images", List.of(
                    Map.of("image_url", Map.of("url", "data:image/png;base64,%%%not-base64%%%")))));

            assertTrue(MediaInferenceHttp.firstImage(httpUrl).isEmpty());
            assertTrue(MediaInferenceHttp.firstImage(badBase64).isEmpty());
        }
    }

    @Nested
    @DisplayName("messageText")
    class MessageText {

        @Test
        @DisplayName("строка и массив частей поддерживаются одинаково")
        void stringAndParts() {
            assertEquals("привет",
                    MediaInferenceHttp.messageText(responseWithMessage(Map.of("content", "привет"))));
            assertEquals("аб", MediaInferenceHttp.messageText(responseWithMessage(Map.of(
                    "content", List.of(
                            Map.of("type", "text", "text", "а"),
                            Map.of("type", "image_url", "image_url", Map.of("url", PNG_DATA_URI)),
                            Map.of("type", "text", "text", "б"))))));
        }

        @Test
        @DisplayName("нет choices/контента → пустая строка, не NPE")
        void missingContent() {
            assertEquals("", MediaInferenceHttp.messageText(Map.of()));
            assertEquals("", MediaInferenceHttp.messageText(responseWithMessage(Map.of())));
        }
    }

    @Nested
    @DisplayName("usage")
    class UsageParsing {

        @Test
        @DisplayName("prompt/completion + cached_tokens из prompt_tokens_details")
        void fullUsage() {
            Map<String, Object> response = Map.of("usage", Map.of(
                    "prompt_tokens", 17,
                    "completion_tokens", 1290,
                    "prompt_tokens_details", Map.of("cached_tokens", 5)));

            Usage usage = MediaInferenceHttp.usage(response);

            assertEquals(17, usage.inputTokens());
            assertEquals(1290, usage.outputTokens());
            assertEquals(5, usage.cacheReadTokens());
        }

        @Test
        @DisplayName("нулевой кэш → null (как zeroToNull на gRPC-границе); нет usage → null")
        void zeroCacheAndAbsentUsage() {
            Usage usage = MediaInferenceHttp.usage(Map.of("usage", Map.of(
                    "prompt_tokens", 1, "completion_tokens", 2,
                    "prompt_tokens_details", Map.of("cached_tokens", 0))));

            assertNull(usage.cacheReadTokens());
            assertNull(MediaInferenceHttp.usage(Map.of()));
        }
    }

    @Test
    @DisplayName("parseDataUri: валидный разбирается, безсхемные/пустые — empty")
    void parseDataUri() {
        Optional<DataUri> parsed = MediaInferenceHttp.parseDataUri(PNG_DATA_URI);
        assertEquals("image/png", parsed.orElseThrow().mime());

        assertTrue(MediaInferenceHttp.parseDataUri(null).isEmpty());
        assertTrue(MediaInferenceHttp.parseDataUri("https://x/y.png").isEmpty());
        assertTrue(MediaInferenceHttp.parseDataUri("data:;base64,AAAA").isEmpty());
        assertTrue(MediaInferenceHttp.parseDataUri(
                "data:image/png;base64," + Base64.getEncoder().encodeToString(new byte[0])).isEmpty());
    }

    @Test
    @DisplayName("сквозной кейс: реалистичный ответ OpenRouter-генерации")
    void realisticGeneration() {
        Map<String, Object> response = Map.of(
                "choices", List.of(Map.of("message", Map.of(
                        "role", "assistant",
                        "content", "картинка готова",
                        "images", List.of(Map.of(
                                "type", "image_url",
                                "image_url", Map.of("url", PNG_DATA_URI)))))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 1290));

        assertEquals("картинка готова", MediaInferenceHttp.messageText(response));
        assertArrayEquals(PNG_BYTES, MediaInferenceHttp.firstImage(response).orElseThrow().bytes());
        assertEquals(1290, MediaInferenceHttp.usage(response).outputTokens());
    }
}
