package ru.agimate.agentworker.workers.run;

import com.openai.core.http.Headers;
import com.openai.errors.InternalServerException;
import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.agent.AgiMateAgent;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.TestTemplates;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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

class LlmCallTest {

    private static RateLimitException rateLimit(Headers headers) {
        return RateLimitException.builder().headers(headers).build();
    }

    /** Waiting budgets for a test: short enough that a stalled provider does not stall the suite. */
    private static AgentProperties.Llm budgets(Duration firstChunk) {
        return budgets(firstChunk, firstChunk);
    }

    private static AgentProperties.Llm budgets(Duration firstChunk, Duration idle) {
        return budgets(firstChunk, idle, Duration.ofMinutes(5));
    }

    private static AgentProperties.Llm budgets(Duration firstChunk, Duration idle, Duration call) {
        AgentProperties.Llm llm = new AgentProperties.Llm();
        llm.setFirstChunkTimeout(firstChunk);
        llm.setIdleTimeout(idle);
        llm.setCallTimeout(call);
        return llm;
    }

    @Nested
    @DisplayName("учёт usage — токены на Reply (репорт делает обвязка рана, не вызов)")
    class UsageReporting {

        private final AgentWorkerClient client = mock(AgentWorkerClient.class);
        private final ModelFactory modelFactory = mock(ModelFactory.class);
        private final LlmMessageMapper mapper = mock(LlmMessageMapper.class);
        private final OpenAiChatModel model = mock(OpenAiChatModel.class);

        private final ResponseTemplates templates = mock(ResponseTemplates.class);

        private final LlmCall llmCall =
                new LlmCall(client, modelFactory, mapper, templates, 3, budgets(Duration.ofSeconds(5)));

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
            // Ответ приходит потоком: содержательный чанк плюс хвостовой со счётчиками токенов.
            ChatResponse content = new ChatResponse(
                    List.of(new Generation(new AssistantMessage("ok"))));
            ChatResponse tail = new ChatResponse(List.of(),
                    ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(content, tail));
        }

        @Test
        @DisplayName("успешный вызов: usage на Reply (provider_id + токены); сам вызов не репортит")
        void carriesUsageOnResult() {
            stubSuccessfulCall("prov-1");

            LlmCall.Reply result = llmCall.call(List.of(), List.of(), "agent-1", "run-1-0");

            assertFalse(result.failed());
            // Текст собран из чанков — наверх уезжает целый ход, а не поток.
            assertEquals("ok", result.assistant().text());
            assertNull(result.incomplete());
            // Provenance для журнала ходов: модель из кредов, callId — тот, что дал вызывающий.
            assertEquals("gpt-5-mini", result.meta().model());
            assertEquals("run-1-0", result.meta().callId());
            LlmUsage usage = result.usage();
            assertEquals("run-1-0", usage.callId());
            assertEquals("prov-1", usage.providerId());
            assertEquals("gpt-5-mini", usage.model());
            assertEquals(100, usage.promptTokens());
            assertEquals(20, usage.completionTokens());
            assertEquals(0, usage.cacheReadTokens());
            assertEquals(0, usage.cacheWriteTokens());
            // Репортит обвязка рана из рекордера — сам вызов на бэк usage не шлёт.
            verify(client, never()).reportLlmUsage(anyString(), anyString(), anyString(), anyString(),
                    anyString(), anyInt(), anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("finish_reason из ответа прокидывается в meta (терминальность решает диспатчер)")
        void carriesFinishReason() {
            stubSuccessfulCall("prov-1");
            when(mapper.finishReason(any())).thenReturn("length");

            LlmCall.Reply result = llmCall.call(List.of(), List.of(), "agent-1", "run-1-0");

            assertFalse(result.failed());
            assertEquals("length", result.meta().finishReason());
        }

        @Test
        @DisplayName("пустой provider_id (старый control-api) → usage не считается (null на Reply)")
        void skipsUsageWithoutProviderId() {
            stubSuccessfulCall("");

            LlmCall.Reply result = llmCall.call(List.of(), List.of(), "agent-1", "run-1-0");

            assertFalse(result.failed());
            assertNull(result.usage());
        }

        @Test
        @DisplayName("RESOURCE_EXHAUSTED (квота): message сервера отдаётся дословно как userFacing")
        void quotaSurfacedAsUserError() {
            String quota = "Дневной лимит токенов провайдера «Openrouter» исчерпан.";
            when(client.getLlmCredentials("agent-1")).thenThrow(new ControlApiCallException(
                    "GetLlmCredentials", Status.RESOURCE_EXHAUSTED.withDescription(quota)));

            LlmCall.Reply result = llmCall.call(List.of(), List.of(), "agent-1", "run-1-0");

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

                LlmCall.Reply result = llmCall.call(List.of(), List.of(), "agent-1", "run-1-0");

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

            LlmCall.Reply result = llmCall.call(List.of(), List.of(), "agent-1", "run-1-0");

            assertTrue(result.failed());
            assertFalse(result.userFacing());
        }
    }

    @Nested
    @DisplayName("тело запроса на проводе")
    class RequestBody {

        private static final String SSE_COMPLETION = """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5",\
                "choices":[{"index":0,"delta":{"role":"assistant","content":"ok"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"moonshotai/kimi-k2.5",\
                "choices":[{"index":0,"delta":{},"finish_reason":"stop"}],\
                "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

                data: [DONE]

                """;

        /**
         * The one check that cannot be replaced by asserting on options: the assembled request is what a
         * provider actually receives. Spring AI 2.0 builds the body from the prompt's options alone, so
         * a field set only on the client's default options leaves no trace here — extra_body travelled
         * that dead path from the model toolRegistry's arrival until this test. OpenRouter-style extensions
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
                byte[] out = SSE_COMPLETION.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
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
                LlmCall llmCall = new LlmCall(client, new ModelFactory(localTargetsAllowed()),
                        new LlmMessageMapper(TestTemplates.of("ru")), mock(ResponseTemplates.class), 1,
                        budgets(Duration.ofSeconds(5)));

                LlmCall.Reply result = llmCall.call(
                        List.of(AgentChatMessage.user("привет")), List.of(), "agent-1", "run-1-0");

                assertFalse(result.failed(), () -> "вызов не дошёл: " + result.message());
                assertEquals("ok", result.assistant().text());
                String body = wireBody.get();
                assertTrue(body.contains("\"stream\":true"),
                        () -> "запрос ушёл не потоковым:\n" + body);
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

        /** Стаб провайдера живёт на loopback — как локальная модель, для которой флаг и существует. */
        private AgentProperties localTargetsAllowed() {
            AgentProperties props = new AgentProperties();
            props.getNet().setAllowPrivateTargets(true);
            return props;
        }
    }

    @Nested
    @DisplayName("обрыв потока: до первого чанка — повтор, после — неполный ход")
    class Streaming {

        private static final String CONTENT_CHUNK = """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"role":"assistant","content":"половина"}}]}

                """;

        private static final String WHOLE_ANSWER = CONTENT_CHUNK + """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        /** Ломаем стрим тем же, чем его ломает сеть: содержательный чанк доехал, следующий — мусор. */
        private static final String BROKEN_AFTER_CONTENT = CONTENT_CHUNK + """
                data: {"choices":[{"index":0,"delta":

                """;

        @Test
        @DisplayName("оборванный после контента стрим не повторяется: собранное — ходом, причина BROKEN")
        void keepsPartialTurnInsteadOfPayingTwice() throws Exception {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = sseServer((exchange, out) -> {
                requests.incrementAndGet();
                body(BROKEN_AFTER_CONTENT).respond(exchange, out);
            });
            try {
                LlmCall.Reply result = callAgainst(server, Duration.ofSeconds(5));

                assertFalse(result.failed(), () -> "ход должен уцелеть: " + result.message());
                assertEquals("половина", result.assistant().text());
                assertEquals(LlmResponseIncomplete.Reason.BROKEN, result.incomplete());
                // Повтор оплатил бы уже сгенерированное второй раз — и почти наверняка тем же обрывом.
                assertEquals(1, requests.get());
            } finally {
                server.stop(0);
            }
        }

        /**
         * Молчание стаба намного длиннее бюджета и тест меряет время: снятая по таймауту попытка
         * должна вернуться по бюджету, а не когда провайдер наконец что-то пришлёт. Закрытие потока в
         * SDK ждёт свой поток чтения, и без read-таймаута OkHttp повтор начался бы только с байтами.
         */
        @Test
        @DisplayName("молчание до первого чанка снимает подписку и повторяется по бюджету — платить не за что")
        void retriesWhileNothingHasArrived() throws Exception {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = sseServer((exchange, out) -> {
                if (requests.incrementAndGet() == 1) {
                    // Провайдер, который «думает» дольше бюджета: заголовки ушли, событий нет.
                    Thread.sleep(4_000);
                }
                body(WHOLE_ANSWER).respond(exchange, out);
            });
            try {
                long startedAt = System.nanoTime();
                LlmCall.Reply result = callAgainst(server, Duration.ofMillis(200));
                long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

                assertFalse(result.failed(), () -> "вторая попытка должна дойти: " + result.message());
                assertEquals("половина", result.assistant().text());
                assertNull(result.incomplete());
                assertEquals(2, requests.get());
                // Бюджет 200 мс + бэкофф 1 с + быстрый повтор; 4 с — это «ждали байтов провайдера».
                assertTrue(elapsedMs < 3_000, () -> "попытка вернулась не по бюджету: " + elapsedMs + " ms");
            } finally {
                server.stop(0);
            }
        }

        /** Первый чанк у OpenAI-стиля: роль и пустое содержимое — на проводе он есть, в ответе его нет. */
        private static final String ROLE_PRELUDE = """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}

                """;

        /**
         * Прелюдия уходит сразу, содержимое — позже бюджета «до первого чанка», но раньше idle. Если бы
         * пустой элемент считался первым чанком, таймер перешёл бы на idle и первая попытка дождалась
         * бы ответа; повтор показывает, что ждали именно содержимого.
         */
        @Test
        @DisplayName("пустая прелюдия не сбрасывает бюджет до первого чанка: ждём содержимого, а не элементов")
        void emptyPreludeDoesNotResetTheFirstChunkBudget() throws Exception {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = sseServer((exchange, out) -> {
                out.write(ROLE_PRELUDE.getBytes(StandardCharsets.UTF_8));
                out.flush();
                if (requests.incrementAndGet() == 1) {
                    Thread.sleep(1_500);
                }
                body(WHOLE_ANSWER).respond(exchange, out);
            });
            try {
                LlmCall.Reply result = callAgainst(server,
                        budgets(Duration.ofMillis(300), Duration.ofSeconds(5)), List.of());

                assertFalse(result.failed(), () -> "вторая попытка должна дойти: " + result.message());
                assertEquals("половина", result.assistant().text());
                assertEquals(2, requests.get());
            } finally {
                server.stop(0);
            }
        }

        /**
         * Потолок должен быть нашим, а не Spring AI: его билдер опций подставляет
         * {@code DEFAULT_TIMEOUT = 60s} во всё, где таймаут не задан, и читает его с опций промпта,
         * так что значение на дефолтных опциях клиента до провода не доезжает. Стаб говорит дольше
         * потолка в полсекунды; с минутой Spring AI ход дошёл бы целым.
         */
        @Test
        @DisplayName("потолок вызова — из конфига: говорливый стрим режется по нему, а не по 60 с Spring AI")
        void callCeilingComesFromConfig() throws Exception {
            HttpServer server = sseServer((exchange, out) -> {
                for (int i = 0; i < 30; i++) {
                    out.write(CONTENT_CHUNK.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(100);
                }
                body(WHOLE_ANSWER).respond(exchange, out);
            });
            try {
                long startedAt = System.nanoTime();
                LlmCall.Reply result = callAgainst(server,
                        budgets(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMillis(500)), List.of());
                long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

                assertFalse(result.failed(), () -> "ход должен уцелеть: " + result.message());
                assertEquals(LlmResponseIncomplete.Reason.BROKEN, result.incomplete());
                assertTrue(result.assistant().text().startsWith("половина"), result.assistant().text());
                assertTrue(elapsedMs < 2_000, () -> "потолок не сработал: " + elapsedMs + " ms");
            } finally {
                server.stop(0);
            }
        }

        /**
         * После первого чанка ждёт idle, а сокет держит read-таймаут длиннее его. Отмена подписки
         * упирается в {@code close()} SDK, который ждёт свой поток чтения до read-таймаута; без
         * {@code cancelOn} ход возвращался бы по нему, а не по idle, и держал бы таймерный поток Reactor.
         */
        @Test
        @DisplayName("тишина после контента: ход возвращается по idle, а не по read-таймауту сокета")
        void idleReturnsAtItsOwnBudget() throws Exception {
            HttpServer server = sseServer((exchange, out) -> {
                out.write(CONTENT_CHUNK.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(8_000);
            });
            try {
                long startedAt = System.nanoTime();
                LlmCall.Reply result = callAgainst(server,
                        budgets(Duration.ofSeconds(5), Duration.ofMillis(300)), List.of());
                long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

                assertFalse(result.failed(), () -> "ход должен уцелеть: " + result.message());
                assertEquals(LlmResponseIncomplete.Reason.BROKEN, result.incomplete());
                assertEquals("половина", result.assistant().text());
                assertTrue(elapsedMs < 2_500, () -> "вернулись не по idle: " + elapsedMs + " ms");
            } finally {
                server.stop(0);
            }
        }

        private static final String TOOL_CALL_STREAM = """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_0",\
                "type":"function","function":{"name":"time_now","arguments":""}}]}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;

        /**
         * Дельты тул-вызова склеивает Spring AI, а исполнять их — наше дело: колбэки, которые мы ему
         * отдаём, бросают исключение при вызове. Тест держит оба факта разом — целый вызов в ходе и
         * отсутствие исключения означают, что внутреннего исполнения тулов в стриминговом пути нет.
         */
        @Test
        @DisplayName("тул-вызов приезжает собранным, со своим id, и Spring AI его не исполняет")
        void assemblesToolCallsWithoutRunningThem() throws Exception {
            HttpServer server = sseServer(body(TOOL_CALL_STREAM));
            try {
                LlmCall.Reply result = callAgainst(server, budgets(Duration.ofSeconds(5)),
                        List.of(new ToolDef("time_now", "current time", "{\"type\":\"object\"}")));

                assertFalse(result.failed(), () -> "вызов не дошёл: " + result.message());
                assertEquals(1, result.assistant().toolCalls().size());
                AgentChatMessage.ToolCall call = result.assistant().toolCalls().get(0);
                assertEquals("time_now", call.name());
                assertEquals("{}", call.argumentsJson());
                // Провайдерский call_0 не переиспользуется: на нашем id висит идемпотентность бэка
                // (само правило чеканки закреплено в StreamAssemblerTest).
                assertTrue(call.id().matches("[a-zA-Z0-9]{9}"), () -> "чужой id доехал: " + call.id());
                // В стриминге Spring AI отдаёт имя enum'а SDK (TOOL_CALLS), а не проводное tool_calls —
                // диалект разбирает диспатчер, и важно, что ход опознан как тул-ход.
                assertEquals(AgiMateAgent.Completion.TOOL_CALLS,
                        LlmCallDispatcher.completion(result.meta().finishReason()));
            } finally {
                server.stop(0);
            }
        }

        private static final String REASONING_STREAM = """
                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"role":"assistant","reasoning_content":"сна"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"reasoning_content":"\\n\\n"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"reasoning_content":"чала"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"content":"от"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m",\
                "choices":[{"index":0,"delta":{"content":"вет"},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        /**
         * Провайдер шлёт {@code reasoning_content} дельтами, но до нас Spring AI доносит в каждом чанке
         * накопленный итог ({@code ChunkMerger}), и тот же итог повторяется на текстовых чанках. Тест
         * держит контракт на живом стабе: рассуждение в ходе — ровно то, что прислал провайдер, один
         * раз, с пробельными дельтами; склейка чанков дала бы его пять раз.
         */
        @Test
        @DisplayName("рассуждение доезжает целиком и один раз: Spring AI отдаёт накопленный итог, не дельты")
        void assemblesReasoningOnce() throws Exception {
            HttpServer server = sseServer(body(REASONING_STREAM));
            try {
                LlmCall.Reply result = callAgainst(server, Duration.ofSeconds(5));

                assertFalse(result.failed(), () -> "вызов не дошёл: " + result.message());
                assertEquals("ответ", result.assistant().text());
                assertEquals("сна\n\nчала", result.assistant().reasoning());
                assertTrue(result.assistant().thinking());
            } finally {
                server.stop(0);
            }
        }

        /**
         * Стаб провайдера на loopback; несколько потоков — иначе повтор ждал бы «думающий» обработчик.
         * Ответ чанкованный: обработчик сам решает, когда байты уходят, — так стаб умеет прислать
         * прелюдию и замолчать.
         */
        private HttpServer sseServer(SseHandler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.createContext("/", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    in.readAllBytes();
                }
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    handler.respond(exchange, os);
                } catch (Exception alreadyGone) {
                    // Клиент снял подписку по таймауту и ушёл — ответ уже некому читать.
                }
            });
            server.start();
            return server;
        }

        private static SseHandler body(String sse) {
            return (exchange, out) -> out.write(sse.getBytes(StandardCharsets.UTF_8));
        }

        private LlmCall.Reply callAgainst(HttpServer server, Duration firstChunk) {
            return callAgainst(server, budgets(firstChunk), List.of());
        }

        private LlmCall.Reply callAgainst(HttpServer server, AgentProperties.Llm budgets, List<ToolDef> toolDefs) {
            LlmCredentials creds = LlmCredentials.newBuilder()
                    .setProviderType("openai_compatible")
                    .setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                    .setApiKey("sk-test")
                    .setModel("m")
                    .setProviderId("prov-1")
                    .build();
            AgentWorkerClient client = mock(AgentWorkerClient.class);
            when(client.getLlmCredentials("agent-1")).thenReturn(creds);
            AgentProperties props = new AgentProperties();
            props.getNet().setAllowPrivateTargets(true);
            // The factory reads the budgets too: the read timeout under the stream and the call ceiling.
            props.setLlm(budgets);
            LlmCall llmCall = new LlmCall(client, new ModelFactory(props),
                    new LlmMessageMapper(TestTemplates.of("ru")), mock(ResponseTemplates.class), 1, budgets);
            return llmCall.call(List.of(AgentChatMessage.user("привет")), toolDefs, "agent-1", "run-1-0");
        }

        @FunctionalInterface
        private interface SseHandler {
            void respond(com.sun.net.httpserver.HttpExchange exchange, OutputStream out) throws Exception;
        }
    }

    @Test
    @DisplayName("таймаут ожидания — повод повторить, хотя провайдер и не при чём")
    void retriesOnOwnTimeout() {
        assertTrue(LlmCall.retryable(new RuntimeException(new TimeoutException("no chunk"))));
        assertTrue(LlmCall.retryable(rateLimit(Headers.builder().build())));
        assertFalse(LlmCall.retryable(
                UnauthorizedException.builder().headers(Headers.builder().build()).build()));
    }

    @Test
    @DisplayName("транзиентные: 429/5xx/сетевые (и в cause-цепочке); терминальные: 401 и не-SDK ошибки")
    void classifiesTransientErrors() {
        assertTrue(LlmCall.transientProviderError(rateLimit(Headers.builder().build())));
        assertTrue(LlmCall.transientProviderError(
                InternalServerException.builder().statusCode(503).headers(Headers.builder().build()).build()));
        assertTrue(LlmCall.transientProviderError(new OpenAIIoException("connect timed out")));
        // Тело, кончившееся посреди стрима, SSE-слой SDK подаёт как «невалидные данные», не как IO.
        assertTrue(LlmCall.transientProviderError(
                new OpenAIInvalidDataException("Unexpected end of stream", new IOException("eof"))));
        // Spring AI оборачивает исключения SDK — классификация ходит по cause-цепочке.
        assertTrue(LlmCall.transientProviderError(
                new RuntimeException(rateLimit(Headers.builder().build()))));

        assertFalse(LlmCall.transientProviderError(
                UnauthorizedException.builder().headers(Headers.builder().build()).build()));
        assertFalse(LlmCall.transientProviderError(
                new IllegalArgumentException("Unsupported provider_type")));
    }

    @Test
    @DisplayName("Retry-After уважается с потолком 30 с; отсутствие/мусор → 0")
    void parsesRetryAfter() {
        assertEquals(7_000, LlmCall.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "7").build())));
        assertEquals(30_000, LlmCall.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "3600").build())));
        assertEquals(0, LlmCall.retryAfterMs(rateLimit(Headers.builder().build())));
        assertEquals(0, LlmCall.retryAfterMs(
                rateLimit(Headers.builder().put("retry-after", "Wed, 21 Oct 2026").build())));
        assertEquals(0, LlmCall.retryAfterMs(new OpenAIIoException("io")));
    }
}
