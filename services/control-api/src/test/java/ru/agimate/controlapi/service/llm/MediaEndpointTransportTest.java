package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.MediaTransportType;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.llm.MediaTransport.GenerationRequest;
import ru.agimate.controlapi.service.llm.MediaTransport.InputImage;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MediaEndpointTransport — тело запроса и чтение ответа медийного эндпойнта")
class MediaEndpointTransportTest {

    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 'P', 'N', 'G'};

    private static ResolvedLlm resolved(Map<String, Object> extraBody) {
        return resolved(extraBody, Map.of());
    }

    private static ResolvedLlm resolved(Map<String, Object> extraBody, Map<String, Object> metadata) {
        LlmProvider provider = LlmProvider.builder()
                .id(UUID.randomUUID())
                .name("polza")
                .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                .baseUrl("https://api.polza.ai/api/v1")
                .mediaTransport(MediaTransportType.MEDIA_ENDPOINT)
                .build();
        return new ResolvedLlm(provider, "google/gemini-2.5-flash-image", "sk-key", extraBody,
                List.of("image", "text"), List.of("image", "text"), metadata, false);
    }

    /** Форма листинга провайдера: параметры модели лежат в top_provider.parameters. */
    private static Map<String, Object> metadataWith(Map<String, Object> parameters) {
        return Map.of("top_provider", Map.of("parameters", parameters));
    }

    @Nested
    @DisplayName("body")
    class Body {

        @Test
        @DisplayName("генерация: model + async=false + input.prompt, без images")
        void generation() {
            Map<String, Object> body = MediaEndpointTransport.body(
                    new GenerationRequest(resolved(Map.of()), "нарисуй кота", List.of()));

            assertEquals("google/gemini-2.5-flash-image", body.get("model"));
            assertEquals(false, body.get("async"));
            Map<?, ?> input = (Map<?, ?>) body.get("input");
            assertEquals("нарисуй кота", input.get("prompt"));
            assertFalse(input.containsKey("images"), "без исходников ключ images не появляется");
        }

        @Test
        @DisplayName("редактирование: исходники — голый base64, без data-URI-префикса")
        void sourcesAsBareBase64() {
            Map<String, Object> body = MediaEndpointTransport.body(new GenerationRequest(
                    resolved(Map.of()), "сделай фон синим",
                    List.of(new InputImage("image/png", PNG_BYTES))));

            List<?> images = (List<?>) ((Map<?, ?>) body.get("input")).get("images");
            assertEquals(1, images.size());
            Map<?, ?> image = (Map<?, ?>) images.get(0);
            assertEquals("base64", image.get("type"));
            assertEquals(Base64.getEncoder().encodeToString(PNG_BYTES), image.get("data"));
        }

        @Test
        @DisplayName("extra_body ложится под ядро: input.aspect_ratio доезжает, prompt не затирается")
        void extraBodyMergesUnderTheCore() {
            Map<String, Object> body = MediaEndpointTransport.body(new GenerationRequest(
                    resolved(Map.of("input", Map.of("aspect_ratio", "16:9"))),
                    "нарисуй кота", List.of()));

            Map<?, ?> input = (Map<?, ?>) body.get("input");
            assertEquals("16:9", input.get("aspect_ratio"));
            assertEquals("нарисуй кота", input.get("prompt"));
        }

        @Test
        @DisplayName("объявленные моделью параметры доезжают, даже если не помечены required")
        void fillsDeclaredParameters() {
            // Форма google/gemini-3.1-flash-lite-image: required нет ни у чего, но без aspect_ratio 400.
            Map<String, Object> body = MediaEndpointTransport.body(new GenerationRequest(
                    resolved(Map.of(), metadataWith(Map.of(
                            "prompt", Map.of("max_length", 16000),
                            "aspect_ratio", Map.of("values", List.of("1:1", "16:9")),
                            "image_resolution", Map.of("values", List.of("1K")),
                            "quality", Map.of("default", "basic", "values", List.of("basic", "high")),
                            "images", Map.of("min", 0, "max", 10)))),
                    "нарисуй кота", List.of()));

            Map<?, ?> input = (Map<?, ?>) body.get("input");
            assertEquals("1:1", input.get("aspect_ratio"), "нет default → первое допустимое значение");
            assertEquals("1K", input.get("image_resolution"));
            assertEquals("basic", input.get("quality"), "default побеждает первое значение");
            assertEquals("нарисуй кота", input.get("prompt"), "prompt берётся из вызова, не из декларации");
            assertFalse(input.containsKey("images"), "images приходят из вызова, декларация их не создаёт");
        }

        @Test
        @DisplayName("параметр без default и values (seed, upscale_factor) не выдумывается")
        void skipsParametersWithoutDeclaredValues() {
            Map<String, Object> defaults = MediaEndpointTransport.declaredDefaults(
                    resolved(Map.of(), metadataWith(Map.of(
                            "seed", Map.of("min", 0, "max", 999),
                            "aspect_ratio", Map.of("default", "1:1")))));

            assertEquals(Map.of("aspect_ratio", "1:1"), defaults);
        }

        @Test
        @DisplayName("extra_body побеждает объявленное значение — настройка руками сильнее листинга")
        void extraBodyWinsOverDeclaredDefault() {
            Map<String, Object> body = MediaEndpointTransport.body(new GenerationRequest(
                    resolved(Map.of("input", Map.of("aspect_ratio", "21:9")),
                            metadataWith(Map.of("aspect_ratio", Map.of("default", "1:1")))),
                    "нарисуй кота", List.of()));

            assertEquals("21:9", ((Map<?, ?>) body.get("input")).get("aspect_ratio"));
        }

        @Test
        @DisplayName("модели нет в реестре — тело как раньше, без выдуманных параметров")
        void noMetadataNoDefaults() {
            Map<String, Object> body = MediaEndpointTransport.body(
                    new GenerationRequest(resolved(Map.of()), "нарисуй кота", List.of()));

            assertEquals(Map.of("prompt", "нарисуй кота"), body.get("input"));
        }
    }

    @Nested
    @DisplayName("чтение ответа")
    class ResponseReading {

        @Test
        @DisplayName("завершённая генерация: ссылка из data[0].url")
        void resultUrl() {
            assertEquals("https://s3.polza.ai/f/1.png", MediaEndpointTransport.resultUrl(
                    Map.of("status", "completed", "data", List.of(Map.of("url", "https://s3.polza.ai/f/1.png")))));
        }

        @Test
        @DisplayName("completed без ссылки — ошибка, а не пустой результат")
        void completedWithoutLink() {
            assertThrows(MediaInferenceException.class,
                    () -> MediaEndpointTransport.resultUrl(Map.of("status", "completed", "data", List.of())));
        }

        @Test
        @DisplayName("failed — ошибка с текстом провайдера")
        void failedStatus() {
            MediaInferenceException e = assertThrows(MediaInferenceException.class,
                    () -> MediaEndpointTransport.requireNotFailed(
                            Map.of("status", "failed", "error", "content policy")));

            assertTrue(e.getMessage().contains("content policy"), e.getMessage());
        }

        @Test
        @DisplayName("ответ без статуса не принимается за успех")
        void missingStatus() {
            assertThrows(MediaInferenceException.class,
                    () -> MediaEndpointTransport.requireNotFailed(Map.of("data", List.of())));
        }

        @Test
        @DisplayName("id для поллинга: requestId у submit, id у статуса")
        void pollId() {
            assertEquals("gen_1", MediaEndpointTransport.id(Map.of("requestId", "gen_1")));
            assertEquals("gen_2", MediaEndpointTransport.id(Map.of("id", "gen_2")));
            assertThrows(MediaInferenceException.class,
                    () -> MediaEndpointTransport.id(Map.of("status", "pending")));
        }
    }
}
