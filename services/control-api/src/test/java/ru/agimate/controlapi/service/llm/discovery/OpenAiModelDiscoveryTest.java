package ru.agimate.controlapi.service.llm.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.model.LlmModelInfo;
import ru.agimate.controlapi.service.http.PublicOnlyHttp;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpenAiModelDiscovery — адрес списка моделей")
class OpenAiModelDiscoveryTest {

    private static final String API_KEY = "sk-test-key";

    @Mock
    private PublicOnlyHttp http;

    @InjectMocks
    private OpenAiModelDiscovery discovery;

    private MockRestServiceServer server;

    private void bindServer() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        when(http.restClient(any())).thenReturn(builder);
    }

    private static LlmProvider provider(String baseUrl) {
        return LlmProvider.builder()
                .id(UUID.randomUUID())
                .userId(UUID.randomUUID())
                .name("moonshot")
                .providerType(LlmProviderType.OPENAI)
                .baseUrl(baseUrl)
                .enabled(true)
                .build();
    }

    @Nested
    @DisplayName("base_url уже содержит версию")
    class VersionInBaseUrl {

        @Test
        @DisplayName("https://api.moonshot.ai/v1 → /v1/models, без удвоения версии")
        void doesNotDoubleVersion() {
            bindServer();
            server.expect(requestTo("https://api.moonshot.ai/v1/models"))
                    .andExpect(method(HttpMethod.GET))
                    .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY))
                    .andRespond(withSuccess(
                            "{\"data\":[{\"id\":\"kimi-k3.1-pro\",\"object\":\"model\"}]}",
                            MediaType.APPLICATION_JSON));

            List<LlmModelInfo> models = discovery.listModels(provider("https://api.moonshot.ai/v1"), API_KEY);

            assertEquals(List.of("kimi-k3.1-pro"), models.stream().map(LlmModelInfo::id).toList());
            server.verify();
        }

        @Test
        @DisplayName("слеш на конце версии не даёт двойного слеша")
        void ignoresTrailingSlash() {
            bindServer();
            server.expect(requestTo("https://api.moonshot.ai/v1/models"))
                    .andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));

            discovery.listModels(provider("https://api.moonshot.ai/v1/"), API_KEY);

            server.verify();
        }
    }

    @Test
    @DisplayName("base_url не задан → дефолт OpenAI вместе с версией")
    void defaultsToOpenAiV1() {
        bindServer();
        server.expect(requestTo("https://api.openai.com/v1/models"))
                .andRespond(withSuccess("{\"data\":[{\"id\":\"gpt-5-mini\"}]}", MediaType.APPLICATION_JSON));

        List<LlmModelInfo> models = discovery.listModels(provider(null), API_KEY);

        assertEquals(List.of("gpt-5-mini"), models.stream().map(LlmModelInfo::id).toList());
        server.verify();
    }
}
