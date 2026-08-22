package ru.agimate.agentworker.workers;

import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmCallWorkflowImplTest {

    private static RateLimitException rateLimit(Headers headers) {
        return RateLimitException.builder().headers(headers).build();
    }

    @Nested
    @DisplayName("учёт usage — токены на Result (репорт делает диспатчер, не воркфлоу)")
    class UsageReporting {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final ModelFactory modelFactory = mock(ModelFactory.class);
        private final LlmMessageMapper mapper = mock(LlmMessageMapper.class);
        private final OpenAiChatModel model = mock(OpenAiChatModel.class);

        /** Реальный DBOS-контекст в юнит-тесте недоступен — подменяем шов call id; runId — параметр. */
        private final ResponseTemplates templates = mock(ResponseTemplates.class);

        private final LlmCallWorkflowImpl workflow = new LlmCallWorkflowImpl(client, modelFactory, mapper, templates) {
            @Override
            String currentCallId() {
                return "wf-llm-77";
            }
        };

        private LlmCredentials creds(String providerId) {
            return LlmCredentials.newBuilder()
                    .setProviderType("openai_compatible")
                    .setBaseUrl("https://openrouter.ai/api/v1")
                    .setApiKey("sk-key")
                    .setModel("gpt-5-mini")
                    .setProviderId(providerId)
                    .build();
        }

        private void stubSuccessfulCall(String providerId) {
            when(client.getLlmCredentials("agent-1")).thenReturn(creds(providerId));
            when(modelFactory.build(any())).thenReturn(model);
            when(mapper.toSpringMessages(any(), any(), anyBoolean())).thenReturn(List.of());
            when(mapper.toolCallbacks(any())).thenReturn(List.of());
            ChatResponse response = new ChatResponse(List.of(),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
            when(model.call(any(Prompt.class))).thenReturn(response);
            when(mapper.fromResponse(eq(response), any())).thenReturn(
                    AgentChatMessage.assistant("ok", false, List.of()));
        }

        @Test
        @DisplayName("успешный вызов: usage на Result (provider_id + токены); воркфлоу сам не репортит")
        void carriesUsageOnResult() {
            stubSuccessfulCall("prov-1");

            LlmCallWorkflow.Result result = workflow.llmCall(List.of(), List.of(), "agent-1");

            assertFalse(result.failed());
            // Provenance для журнала ходов: модель из кредов, callId = собственный workflow id вызова.
            assertEquals("gpt-5-mini", result.model());
            assertEquals("wf-llm-77", result.callId());
            LlmUsage usage = result.usage();
            assertEquals("wf-llm-77", usage.callId());
            assertEquals("prov-1", usage.providerId());
            assertEquals("gpt-5-mini", usage.model());
            assertEquals(100, usage.promptTokens());
            assertEquals(20, usage.completionTokens());
            assertEquals(0, usage.cacheReadTokens());
            assertEquals(0, usage.cacheWriteTokens());
            // Репортит родитель (ран-обвязка из sink'а) — сам воркфлоу на бэк usage не шлёт.
            verify(client, never()).reportLlmUsage(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyInt(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("finish_reason из ответа прокидывается в Result (терминальность решает диспатчер)")
        void carriesFinishReason() {
            stubSuccessfulCall("prov-1");
            when(mapper.finishReason(any())).thenReturn("length");

            LlmCallWorkflow.Result result = workflow.llmCall(List.of(), List.of(), "agent-1");

            assertFalse(result.failed());
            assertEquals("length", result.finishReason());
        }

        @Test
        @DisplayName("пустой provider_id (старый control-api) → usage не считается (null на Result)")
        void skipsUsageWithoutProviderId() {
            stubSuccessfulCall("");

            LlmCallWorkflow.Result result = workflow.llmCall(List.of(), List.of(), "agent-1");

            assertFalse(result.failed());
            assertNull(result.usage());
        }

        @Test
        @DisplayName("RESOURCE_EXHAUSTED (квота): message сервера отдаётся дословно как userFacing")
        void quotaSurfacedAsUserError() {
            String quota = "Дневной лимит токенов провайдера «Openrouter» исчерпан.";
            when(client.getLlmCredentials("agent-1")).thenThrow(new ControlApiCallException(
                    "GetLlmCredentials", Status.RESOURCE_EXHAUSTED.withDescription(quota)));

            LlmCallWorkflow.Result result = workflow.llmCall(List.of(), List.of(), "agent-1");

            assertTrue(result.failed());
            assertTrue(result.userFacing());
            assertEquals(quota, result.message());
            verify(modelFactory, never()).build(any());
        }

        @Test
        @DisplayName("NOT_FOUND/FAILED_PRECONDITION (нет модели) → нотис «настрой модель», не generic")
        void missingModelSurfacedAsSetupNotice() {
            when(templates.noModel()).thenReturn("Настрой модель агенту.");
            for (Status status : List.of(
                    Status.NOT_FOUND.withDescription("No LLM binding for agent 019f…"),
                    Status.FAILED_PRECONDITION.withDescription("LLM provider disabled"))) {
                // doThrow, не when(...): повторный when() на уже бросающем стабе сам получил бы исключение.
                doThrow(new ControlApiCallException("GetLlmCredentials", status))
                        .when(client).getLlmCredentials("agent-1");

                LlmCallWorkflow.Result result = workflow.llmCall(List.of(), List.of(), "agent-1");

                assertTrue(result.failed());
                assertTrue(result.userFacing());
                // Текст сервера («No LLM binding for agent <uuid>») пользователю не показываем.
                assertEquals("Настрой модель агенту.", result.message());
            }
            verify(modelFactory, never()).build(any());
        }

        @Test
        @DisplayName("прочий отказ кредов (не квота, не отсутствие модели) → generic failure без userFacing")
        void otherCredentialFailureStaysGeneric() {
            when(client.getLlmCredentials("agent-1")).thenThrow(new ControlApiCallException(
                    "GetLlmCredentials", Status.INTERNAL.withDescription("boom")));

            LlmCallWorkflow.Result result = workflow.llmCall(List.of(), List.of(), "agent-1");

            assertTrue(result.failed());
            assertFalse(result.userFacing());
        }
    }

    @Nested
    @DisplayName("тело запроса на проводе")
    class RequestBody {

        private static final String COMPLETION = """
                {"id":"cmpl-1","object":"chat.completion","created":1,"model":"moonshotai/kimi-k2.5",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                """;

        /**
         * The one check that cannot be replaced by asserting on options: the assembled request is what a
         * provider actually receives. Spring AI 2.0 builds the body from the prompt's options alone, so
         * a field set only on the client's default options leaves no trace here — extra_body travelled
         * that dead path from the model registry's arrival until this test. OpenRouter-style extensions
         * (provider routing, require_parameters) are unknown to the OpenAI schema and reach the provider
         * through extra_body or not at all. A local stub server stands in for the provider; no network.
         */
        @Test
        @DisplayName("extra_body из кредов доезжает до провайдера вместе с моделью")
        void extraBodyReachesTheProvider() throws Exception {
            AtomicReference<String> wireBody = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    wireBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                byte[] out = COMPLETION.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            });
            server.start();
            try {
                LlmCredentials creds = LlmCredentials.newBuilder()
                        .setProviderType("openai_compatible")
                        .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                        .setApiKey("sk-test")
                        .setModel("moonshotai/kimi-k2.5")
                        .setProviderId("prov-1")
                        .setExtraBodyJson("{\"provider\":{\"only\":[\"moonshotai\"],\"require_parameters\":true}}")
                        .build();
                AgentWorkerClient client = mock(AgentWorkerClient.class);
                when(client.getLlmCredentials("agent-1")).thenReturn(creds);
                LlmCallWorkflowImpl workflow = new LlmCallWorkflowImpl(client,
                        new ModelFactory(new AgentProperties()), new LlmMessageMapper(),
                        mock(ResponseTemplates.class)) {
                    @Override
                    String currentCallId() {
                        return "wf-llm-1";
                    }
                };

                LlmCallWorkflow.Result result = workflow.llmCall(
                        List.of(AgentChatMessage.user("привет")), List.of(), "agent-1");

                assertFalse(result.failed(), () -> "вызов не дошёл: " + result.message());
                String body = wireBody.get();
                assertTrue(body.contains("\"model\":\"moonshotai/kimi-k2.5\""),
                        () -> "модель из кредов не в теле запроса:\n" + body);
                assertTrue(body.contains("\"provider\""), () -> "нет provider-блока extra_body:\n" + body);
                assertTrue(body.contains("\"only\":[\"moonshotai\"]"),
                        () -> "нет значения only из extra_body:\n" + body);
                assertTrue(body.contains("\"require_parameters\":true"),
                        () -> "нет require_parameters из extra_body:\n" + body);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    @DisplayName("транзиентные: 429/5xx/сетевые (и в cause-цепочке); терминальные: 401 и не-SDK ошибки")
    void classifiesTransientErrors() {
        assertTrue(LlmCallWorkflowImpl.transientProviderError(rateLimit(Headers.builder().build())));
        assertTrue(LlmCallWorkflowImpl.transientProviderError(
                InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build()));
        assertTrue(LlmCallWorkflowImpl.transientProviderError(new OpenAIIoException("connect timed out")));
        // Spring AI оборачивает исключения SDK — классификация ходит по cause-цепочке.
        assertTrue(LlmCallWorkflowImpl.transientProviderError(
                new RuntimeException(rateLimit(Headers.builder().build()))));

        assertFalse(LlmCallWorkflowImpl.transientProviderError(
                UnauthorizedException.builder().headers(Headers.builder().build()).build()));
        assertFalse(LlmCallWorkflowImpl.transientProviderError(
                new IllegalArgumentException("Unsupported provider_type")));
    }

    @Test
    @DisplayName("Retry-After уважается с потолком 30 с; отсутствие/мусор → 0")
    void parsesRetryAfter() {
        assertEquals(7_000, LlmCallWorkflowImpl.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "7").build())));
        assertEquals(30_000, LlmCallWorkflowImpl.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "3600").build())));
        assertEquals(0, LlmCallWorkflowImpl.retryAfterMs(rateLimit(Headers.builder().build())));
        assertEquals(0, LlmCallWorkflowImpl.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "Wed, 21 Oct 2026").build())));
        assertEquals(0, LlmCallWorkflowImpl.retryAfterMs(new OpenAIIoException("io")));
    }
}
