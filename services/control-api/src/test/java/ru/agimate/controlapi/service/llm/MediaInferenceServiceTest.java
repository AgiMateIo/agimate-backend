package ru.agimate.controlapi.service.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
    private final MediaCall call = new MediaCall(userId, agentId, "tc-42");

    @Mock
    private LlmCredentialsResolver credentialsResolver;
    @Mock
    private MediaInferenceHttp http;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private LlmUsageService llmUsageService;
    @InjectMocks
    private MediaInferenceService service;

    private final LlmProvider provider = LlmProvider.builder()
            .id(UUID.randomUUID())
            .providerType(LlmProviderType.OPENAI_COMPATIBLE)
            .baseUrl("https://openrouter.ai/api/v1")
            .enabled(true)
            .build();

    private ResolvedLlm resolved(String model, Map<String, Object> extraBody) {
        return new ResolvedLlm(provider, model, "sk-key", extraBody, java.util.List.of(), false);
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

            ImageResult result = service.generateImage(call, "нарисуй кота", null);

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

            ImageResult result = service.generateImage(call, "нарисуй запрещённое", null);

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

            service.generateImage(call, "сделай фон синим", "agf_src");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
            verify(http).chatCompletions(any(), anyString(), body.capture());
            List<?> content = (List<?>) ((Map<?, ?>) ((List<?>) body.getValue().get("messages")).get(0))
                    .get("content");
            assertEquals(Map.of("type", "text", "text", "сделай фон синим"), content.get(0));
            assertEquals(Map.of("type", "image_url", "image_url", Map.of(
                            "url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))),
                    content.get(1));
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
