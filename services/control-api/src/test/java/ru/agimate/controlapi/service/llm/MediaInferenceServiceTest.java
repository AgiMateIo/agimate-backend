package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.service.LlmUsageService;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.llm.MediaInferenceService.ImageResult;
import ru.agimate.controlapi.service.llm.MediaInferenceService.MediaCall;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.FileStorageService.FileContent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaInferenceService — генерация и зрение через модель-инструмент")
class MediaInferenceServiceTest {

    private static final byte[] PNG_BYTES = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
    private static final String PNG_DATA_URI =
            "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_BYTES);

    private final UUID userId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final MediaCall call = new MediaCall(userId, agentId, runId, "tc-42");

    @Mock
    private LlmCredentialsResolver credentialsResolver;
    @Mock
    private MediaInferenceHttp http;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private LlmUsageService llmUsageService;

    private MediaInferenceService service;

    @BeforeEach
    void setUp() {
        // Реальный CHAT_MODALITIES поверх замоканного http: тесты проверяют тело запроса, а не диспетчер.
        MediaTransportRegistry transports =
                new MediaTransportRegistry(List.of(new ChatModalitiesTransport(http)));
        service = new MediaInferenceService(
                credentialsResolver, http, transports, fileStorageService, llmUsageService);
    }

    private final LlmProvider provider = LlmProvider.builder()
            .id(UUID.randomUUID())
            .name("openrouter")
            .providerType(LlmProviderType.OPENAI_COMPATIBLE)
            .baseUrl("https://openrouter.ai/api/v1")
            .enabled(true)
            .build();

    /** Модель, которой реестр не знает: модальности не объявлены — путь не должен упираться в guard. */
    private ResolvedLlm resolved(String model, Map<String, Object> extraBody) {
        return resolved(model, extraBody, List.of(), List.of());
    }

    private ResolvedLlm resolved(String model, Map<String, Object> extraBody,
                                 List<String> inputModalities, List<String> outputModalities) {
        return new ResolvedLlm(provider, model, "sk-key", extraBody,
                inputModalities, outputModalities, Map.of(), false);
    }

    private static Map<String, Object> imageResponse(String text) {
        return Map.of(
                "choices", List.of(Map.of("message", Map.of(
                        "content", text,
                        "images", List.of(Map.of(
                                "type", "image_url",
                                "image_url", Map.of("url", PNG_DATA_URI)))))),
                "usage", Map.of("prompt_tokens", 10, "completion_tokens", 1290));
    }

    @Nested
    @DisplayName("generateImage")
    class GenerateImage {

        @Test
        @DisplayName("happy path: тело с modalities и extra_body, байты в storage, usage учтён")
        void happyPath() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("img-model", Map.of("provider", Map.of("only", List.of("google")))));
            when(http.chatCompletions(eq(provider), eq("sk-key"), any()))
                    .thenReturn(imageResponse("готово"));
            StoredFile stored = StoredFile.builder().id(UUID.randomUUID()).mime("image/png").build();
            when(fileStorageService.store(eq(userId), eq("media:img-model"), eq("image/png"),
                    eq((long) PNG_BYTES.length), any(), eq(null))).thenReturn(stored);

            ImageResult result = service.generateImage(call, "нарисуй кота", List.of());

            assertEquals(stored, result.file());
            assertEquals("готово", result.text());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
            verify(http).chatCompletions(eq(provider), eq("sk-key"), body.capture());
            assertEquals("img-model", body.getValue().get("model"));
            assertEquals(List.of("image", "text"), body.getValue().get("modalities"));
            assertEquals(Map.of("only", List.of("google")), body.getValue().get("provider"));
            assertEquals(List.of(Map.of("role", "user", "content", "нарисуй кота")),
                    body.getValue().get("messages"));

            ArgumentCaptor<LlmUsageService.UsageReport> usage =
                    ArgumentCaptor.forClass(LlmUsageService.UsageReport.class);
            verify(llmUsageService).record(usage.capture());
            assertEquals("media:tc-42", usage.getValue().callId());
            assertEquals(runId, usage.getValue().runId());
            assertEquals(1290, usage.getValue().outputTokens());
            assertEquals(provider.getId(), usage.getValue().providerId());
        }

        @Test
        @DisplayName("отказ модели (текст без картинки): file=null, текст — результат; usage учтён")
        void refusalReturnsTextOnly() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("img-model", Map.of()));
            when(http.chatCompletions(any(), anyString(), any())).thenReturn(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", "не буду рисовать")))));

            ImageResult result = service.generateImage(call, "нарисуй запрещённое", List.of());

            assertNull(result.file());
            assertEquals("не буду рисовать", result.text());
            verify(fileStorageService, never()).store(any(), any(), any(), anyLong(), any(), any());
            // usage в ответе не было — нули, но факт вызова учтён
            ArgumentCaptor<LlmUsageService.UsageReport> usage =
                    ArgumentCaptor.forClass(LlmUsageService.UsageReport.class);
            verify(llmUsageService).record(usage.capture());
            assertEquals(0, usage.getValue().outputTokens());
        }

        @Test
        @DisplayName("edit-режим: исходник уезжает data-URI-частью рядом с промптом")
        void editSendsSourceImage() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("img-model", Map.of()));
            stubOpenImage("agf_src", "image/jpeg", new byte[]{1, 2, 3});
            when(http.chatCompletions(any(), anyString(), any())).thenReturn(imageResponse(""));
            when(fileStorageService.store(any(), any(), any(), anyLong(), any(), any()))
                    .thenReturn(StoredFile.builder().id(UUID.randomUUID()).build());

            service.generateImage(call, "сделай фон синим", List.of("agf_src"));

            List<?> content = capturedContent();
            assertEquals(Map.of("type", "text", "text", "сделай фон синим"), content.get(0));
            assertEquals(imagePart("image/jpeg", new byte[]{1, 2, 3}), content.get(1));
        }

        @Test
        @DisplayName("композиция: картинки уезжают частями в порядке списка")
        void combineSendsAllSourcesInOrder() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("img-model", Map.of()));
            stubOpenImage("agf_a", "image/png", new byte[]{1});
            stubOpenImage("agf_b", "image/jpeg", new byte[]{2});
            when(http.chatCompletions(any(), anyString(), any())).thenReturn(imageResponse(""));
            when(fileStorageService.store(any(), any(), any(), anyLong(), any(), any()))
                    .thenReturn(StoredFile.builder().id(UUID.randomUUID()).build());

            service.generateImage(call, "человека с image 1 в сцену image 2",
                    List.of("agf_a", "agf_b"));

            List<?> content = capturedContent();
            assertEquals(3, content.size());
            assertEquals(Map.of("type", "text", "text", "человека с image 1 в сцену image 2"),
                    content.get(0));
            assertEquals(imagePart("image/png", new byte[]{1}), content.get(1));
            assertEquals(imagePart("image/jpeg", new byte[]{2}), content.get(2));
        }

        @Test
        @DisplayName("модель не объявляет image в output — отказ до HTTP-вызова")
        void rejectsModelThatCannotDrawAccordingToRegistry() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("chat-model", Map.of(),
                            List.of("text", "image"), List.of("text")));

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> service.generateImage(call, "нарисуй кота", List.of()));

            assertTrue(e.getMessage().contains("chat-model"), e.getMessage());
            assertTrue(e.getMessage().contains("cannot generate images"), e.getMessage());
            verify(http, never()).chatCompletions(any(), anyString(), any());
        }

        @Test
        @DisplayName("модели нет в реестре (модальности не объявлены) — вызов идёт, guard не мешает")
        void unknownModalitiesPassTheGuard() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("hand-typed-model", Map.of(), List.of(), List.of()));
            when(http.chatCompletions(any(), anyString(), any())).thenReturn(imageResponse(""));
            when(fileStorageService.store(any(), any(), any(), anyLong(), any(), any()))
                    .thenReturn(StoredFile.builder().id(UUID.randomUUID()).build());

            service.generateImage(call, "нарисуй кота", List.of());

            verify(http).chatCompletions(any(), anyString(), any());
        }

        @Test
        @DisplayName("больше четырёх входных картинок — отказ до HTTP-вызова")
        void rejectsTooManySources() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("img-model", Map.of()));

            MediaInferenceException e = assertThrows(MediaInferenceException.class,
                    () -> service.generateImage(call, "склей",
                            List.of("agf_1", "agf_2", "agf_3", "agf_4", "agf_5")));

            assertTrue(e.getMessage().contains("too many input images"), e.getMessage());
            verify(http, never()).chatCompletions(any(), anyString(), any());
        }

        @Test
        @DisplayName("суммарный бюджет входных картинок — отказ до HTTP-вызова")
        void rejectsOversizedTotal() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.IMAGE))
                    .thenReturn(resolved("img-model", Map.of()));
            byte[] big = new byte[13 * 1024 * 1024];
            stubOpenImage("agf_a", "image/png", big);
            stubOpenImage("agf_b", "image/png", big);

            MediaInferenceException e = assertThrows(MediaInferenceException.class,
                    () -> service.generateImage(call, "склей", List.of("agf_a", "agf_b")));

            assertTrue(e.getMessage().contains("too large together"), e.getMessage());
            verify(http, never()).chatCompletions(any(), anyString(), any());
        }

        private List<?> capturedContent() {
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
            verify(http).chatCompletions(any(), anyString(), body.capture());
            return (List<?>) ((Map<?, ?>) ((List<?>) body.getValue().get("messages")).get(0))
                    .get("content");
        }
    }

    @Nested
    @DisplayName("describeImage")
    class DescribeImage {

        @Test
        @DisplayName("вопрос + data-URI картинки → текст ответа; usage учтён")
        void happyPath() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.VISION))
                    .thenReturn(resolved("vision-model", Map.of()));
            stubOpenImage("agf_img", "image/png", PNG_BYTES);
            when(http.chatCompletions(any(), anyString(), any())).thenReturn(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", "на фото кот"))),
                    "usage", Map.of("prompt_tokens", 300, "completion_tokens", 20)));

            String answer = service.describeImage(call, "agf_img", "кто на фото?");

            assertEquals("на фото кот", answer);
            verify(llmUsageService).record(any());
        }

        @Test
        @DisplayName("модель не объявляет image во входе — отказ до чтения файла")
        void rejectsModelThatCannotSeeAccordingToRegistry() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.VISION))
                    .thenReturn(resolved("text-model", Map.of(), List.of("text"), List.of("text")));

            NoCapableModelException e = assertThrows(NoCapableModelException.class,
                    () -> service.describeImage(call, "agf_img", null));

            assertTrue(e.getMessage().contains("cannot accept images"), e.getMessage());
            verify(fileStorageService, never()).open(any(), anyString());
            verify(http, never()).chatCompletions(any(), anyString(), any());
        }

        @Test
        @DisplayName("не-image файл отвергается до HTTP-вызова")
        void rejectsNonImage() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.VISION))
                    .thenReturn(resolved("vision-model", Map.of()));
            stubOpenFile("agf_doc", "application/pdf", new byte[]{1}, 1L);

            MediaInferenceException e = assertThrows(MediaInferenceException.class,
                    () -> service.describeImage(call, "agf_doc", null));

            assertTrue(e.getMessage().contains("not an image"), e.getMessage());
            verify(http, never()).chatCompletions(any(), anyString(), any());
        }

        @Test
        @DisplayName("слишком большая картинка отвергается до чтения байтов")
        void rejectsOversized() {
            when(credentialsResolver.resolveForCapability(agentId, userId, LlmPurpose.VISION))
                    .thenReturn(resolved("vision-model", Map.of()));
            stubOpenFile("agf_big", "image/png", PNG_BYTES, 21L * 1024 * 1024);

            assertThrows(MediaInferenceException.class,
                    () -> service.describeImage(call, "agf_big", null));
            verify(http, never()).chatCompletions(any(), anyString(), any());
        }
    }

    private static Map<String, Object> imagePart(String mime, byte[] bytes) {
        return Map.of("type", "image_url", "image_url", Map.of(
                "url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes)));
    }

    private void stubOpenImage(String fileId, String mime, byte[] bytes) {
        stubOpenFile(fileId, mime, bytes, (long) bytes.length);
    }

    private void stubOpenFile(String fileId, String mime, byte[] bytes, Long sizeBytes) {
        StoredFile file = StoredFile.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .mime(mime)
                .sizeBytes(sizeBytes)
                .build();
        InputStream content = new ByteArrayInputStream(bytes);
        when(fileStorageService.open(userId, fileId)).thenReturn(new FileContent(file, content));
    }
}
