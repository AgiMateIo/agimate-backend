package ru.agimate.controlapi.connectors.internal.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.StoredFile;
import ru.agimate.controlapi.service.llm.MediaInferenceService;
import ru.agimate.controlapi.service.llm.MediaInferenceService.ImageResult;
import ru.agimate.controlapi.service.llm.MediaInferenceService.MediaCall;
import ru.agimate.controlapi.service.llm.NoCapableModelException;
import ru.agimate.controlapi.storage.FileIds;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("media-коннектор — тулы через executeTool (env биндится по-настоящему)")
class MediaToolServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID agentId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final ConnectorEnv env = new ConnectorEnv(
            null, userId, agentId, runId, null, null, Map.of(), null);

    @Mock
    private MediaInferenceService mediaInferenceService;
    private MediaConnectorService handler;

    @BeforeEach
    void setUp() {
        handler = new MediaConnectorService(new MediaToolService(mediaInferenceService));
    }

    @Test
    @DisplayName("gen_image: identity из env, результат — {\"file\": {...}} + текст модели")
    void genImageReturnsFileRef() {
        UUID storedId = UUID.randomUUID();
        StoredFile stored = StoredFile.builder()
                .id(storedId).mime("image/png").sizeBytes(384_211L).build();
        when(mediaInferenceService.generateImage(any(), eq("кот в сапогах"), isNull()))
                .thenReturn(new ImageResult(stored, "вот ваш кот"));

        Map<String, Object> result = handler.executeTool(env, "gen_image",
                Map.of("prompt", "кот в сапогах"));

        assertEquals(Map.of(
                "id", FileIds.external(storedId),
                "mime", "image/png",
                "size", 384_211L), result.get("file"));
        assertEquals("вот ваш кот", result.get("text"));

        var callCaptor = org.mockito.ArgumentCaptor.forClass(MediaCall.class);
        verify(mediaInferenceService).generateImage(callCaptor.capture(), eq("кот в сапогах"), isNull());
        assertEquals(userId, callCaptor.getValue().userId());
        assertEquals(agentId, callCaptor.getValue().agentId());
        assertEquals(runId, callCaptor.getValue().runId());
        assertNotNull(callCaptor.getValue().callId());
    }

    @Test
    @DisplayName("gen_image: отказ модели → {\"text\": ...} без file, не ошибка")
    void genImageRefusal() {
        when(mediaInferenceService.generateImage(any(), any(), isNull()))
                .thenReturn(new ImageResult(null, "рисовать такое не буду"));

        Map<String, Object> result = handler.executeTool(env, "gen_image", Map.of("prompt", "x"));

        assertFalse(result.containsKey("file"));
        assertEquals("рисовать такое не буду", result.get("text"));
    }

    @Test
    @DisplayName("edit_image: fileId уезжает исходником в generateImage")
    void editImagePassesSource() {
        when(mediaInferenceService.generateImage(any(), eq("синий фон"), eq("agf_src")))
                .thenReturn(new ImageResult(
                        StoredFile.builder().id(UUID.randomUUID()).mime("image/png").sizeBytes(1L).build(),
                        null));

        Map<String, Object> result = handler.executeTool(env, "edit_image",
                Map.of("fileId", "agf_src", "prompt", "синий фон"));

        assertTrue(result.containsKey("file"));
        verify(mediaInferenceService).generateImage(any(), eq("синий фон"), eq("agf_src"));
    }

    @Test
    @DisplayName("read_image: ответ зрения в {\"answer\": ...}")
    void readImage() {
        when(mediaInferenceService.describeImage(any(), eq("agf_img"), eq("кто тут?")))
                .thenReturn("на фото кот");

        Map<String, Object> result = handler.executeTool(env, "read_image",
                Map.of("fileId", "agf_img", "question", "кто тут?"));

        assertEquals(Map.of("answer", "на фото кот"), result);
    }

    @Test
    @DisplayName("доменное исключение доезжает ConnectorException'ом с тем же текстом")
    void domainExceptionSurfacesAsConnectorException() {
        when(mediaInferenceService.generateImage(any(), any(), isNull()))
                .thenThrow(new NoCapableModelException("No model capable of generating image is available"));

        ConnectorException e = assertThrows(ConnectorException.class,
                () -> handler.executeTool(env, "gen_image", Map.of("prompt", "x")));

        assertTrue(e.getMessage().contains("No model capable of generating image"), e.getMessage());
    }

    @Test
    @DisplayName("env без agent identity (глобальная таска) → внятный отказ")
    void missingIdentityRejected() {
        ConnectorEnv noAgent = new ConnectorEnv(null, userId, null, null, null, null, Map.of(), null);

        assertThrows(ConnectorException.class,
                () -> handler.executeTool(noAgent, "gen_image", Map.of("prompt", "x")));
        org.mockito.Mockito.verifyNoInteractions(mediaInferenceService);
    }

    @Test
    @DisplayName("схемы тулов собираются рефлексией: ровно три тула со схемами")
    void toolSpecsReflected() {
        var tools = handler.getTools();

        assertEquals(java.util.Set.of("gen_image", "edit_image", "read_image"), tools.keySet());
        assertNotNull(tools.get("read_image").inputSchema());
    }

    @Test
    @DisplayName("бюджеты ожидания: генерация 30 минут, зрение 5")
    void timeoutBudgetsDeclared() {
        var tools = handler.getTools();

        assertEquals(MediaToolService.GENERATION_TIMEOUT_SECONDS, tools.get("gen_image").timeoutSeconds());
        assertEquals(MediaToolService.GENERATION_TIMEOUT_SECONDS, tools.get("edit_image").timeoutSeconds());
        assertEquals(MediaToolService.VISION_TIMEOUT_SECONDS, tools.get("read_image").timeoutSeconds());
    }
}
