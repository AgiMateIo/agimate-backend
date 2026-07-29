package ru.agimate.controlapi.service.llm.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import ru.agimate.controlapi.database.model.LlmModelInfo;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("LlmDiscoveryHttp — парсинг метаданных модели из /models")
class LlmDiscoveryHttpTest {

    private static ClientHttpResponse response(String body) throws Exception {
        ClientHttpResponse res = mock(ClientHttpResponse.class);
        when(res.getStatusCode()).thenReturn(HttpStatus.OK);
        when(res.getBody()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return res;
    }

    @Test
    @DisplayName("OpenRouter-стиль: модальности, context_length, top_provider.max_completion_tokens, raw")
    void parsesOpenRouterEntry() throws Exception {
        String json = """
            { "data": [ {
                "id": "google/gemini-3-pro-image",
                "name": "Gemini 3 Pro Image",
                "context_length": 1048576,
                "architecture": { "input_modalities": ["text","image"],
                                  "output_modalities": ["image","text"] },
                "top_provider": { "max_completion_tokens": 8192 },
                "supported_parameters": ["tools","reasoning"]
            } ] }""";

        List<LlmModelInfo> models = LlmDiscoveryHttp.extractModels(response(json), "data", "id", "name");

        assertEquals(1, models.size());
        LlmModelInfo m = models.get(0);
        assertEquals("google/gemini-3-pro-image", m.id());
        assertEquals("Gemini 3 Pro Image", m.displayName());
        assertEquals(1048576, m.contextWindow());
        assertEquals(8192, m.maxOutputTokens());
        assertEquals(List.of("text", "image"), m.inputModalities());
        assertEquals(List.of("image", "text"), m.outputModalities());
        assertEquals(List.of("tools", "reasoning"), m.supportedParameters());
        // raw_metadata сохраняет весь entry (в т.ч. непромотированные поля)
        assertEquals("Gemini 3 Pro Image", m.rawMetadata().get("name"));
    }

    @Test
    @DisplayName("голый id без метаданных (OpenAI-стиль) → всё null, кроме id")
    void parsesBareEntry() throws Exception {
        List<LlmModelInfo> models = LlmDiscoveryHttp.extractModels(
                response("{ \"data\": [ { \"id\": \"whisper-1\" } ] }"), "data", "id", null);

        LlmModelInfo m = models.get(0);
        assertEquals("whisper-1", m.id());
        assertNull(m.contextWindow());
        assertNull(m.inputModalities());
        assertNull(m.outputModalities());
        assertNull(m.maxOutputTokens());
    }
}
