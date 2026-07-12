package ru.agimate.controlapi.grpc.service;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.RunContext;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContext;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.SystemSkillBootstrap;
import ru.agimate.controlapi.service.runcontext.RunBlock;
import ru.agimate.controlapi.service.runcontext.RunContextService;
import ru.agimate.controlapi.service.runcontext.RunContextView;
import ru.agimate.controlapi.service.runcontext.RunHistoryMessage;
import ru.agimate.controlapi.service.runcontext.RunTool;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Контрактный тест gRPC-маппинга GetRunContext: каждая коллекция {@link RunContextView} обязана
 * доехать до proto-ответа целиком. Появился после бага B1 — история собиралась сервисом, но
 * не добавлялась в RunContext, и ни один тест по обе стороны шва этого не видел.
 */
@DisplayName("AgentContextGrpcService — маппинг RunContextView → proto RunContext")
class AgentContextGrpcServiceTest {

    private final RunContextService runContextService = mock(RunContextService.class);
    private final AgentLlmRepository agentLlmRepository = mock(AgentLlmRepository.class);
    private final LlmProviderRepository llmProviderRepository = mock(LlmProviderRepository.class);
    private final LlmProviderService llmProviderService = mock(LlmProviderService.class);
    private final AgentContextGrpcService service = new AgentContextGrpcService(
            runContextService,
            mock(ru.agimate.controlapi.service.trigger.RunActivityService.class),
            agentLlmRepository,
            llmProviderRepository,
            llmProviderService);

    @Test
    @DisplayName("все четыре коллекции view доезжают до ответа: блоки, тулы и история")
    void mapsEveryViewCollection() throws Exception {
        RunContextView view = new RunContextView(
                List.of(RunBlock.trusted("agent", "agent", "- id: a-1", Map.of("k", "v"))),
                List.of(new RunBlock("event", "connector:time", "{}", Map.of(), false, true)),
                List.of(new RunTool(
                        new ConnectorToolSpec("get_tasks", null, "desc", null, null, null, null),
                        "board", "conn-1", "board")),
                List.of(
                        new RunHistoryMessage(ChannelSessionMessageKind.INBOUND, "привет"),
                        new RunHistoryMessage(ChannelSessionMessageKind.PROGRESS, "🔧 get_tasks"),
                        new RunHistoryMessage(ChannelSessionMessageKind.ANSWER, "готово"),
                        new RunHistoryMessage(ChannelSessionMessageKind.ERROR, "упс")));
        when(runContextService.build(any(), any())).thenReturn(view);

        RunContext response = callGetRunContext();

        assertEquals(1, response.getSystemBlocksCount());
        assertEquals("agent", response.getSystemBlocks(0).getName());
        assertTrue(response.getSystemBlocks(0).getTrusted());
        assertEquals("v", response.getSystemBlocks(0).getAttrsMap().get("k"));

        assertEquals(1, response.getUserBlocksCount());
        assertEquals("event", response.getUserBlocks(0).getName());
        assertTrue(response.getUserBlocks(0).getEphemeral());

        assertEquals(1, response.getToolsCount());
        assertEquals("get_tasks", response.getTools(0).getName());
        assertEquals("board", response.getTools(0).getConnectorCode());

        assertEquals(4, response.getHistoryCount());
        assertEquals(MessageKind.MESSAGE_KIND_INBOUND, response.getHistory(0).getKind());
        assertEquals("привет", response.getHistory(0).getText());
        assertEquals(MessageKind.MESSAGE_KIND_PROGRESS, response.getHistory(1).getKind());
        assertEquals(MessageKind.MESSAGE_KIND_ANSWER, response.getHistory(2).getKind());
        assertEquals(MessageKind.MESSAGE_KIND_ERROR, response.getHistory(3).getKind());
    }

    @Test
    @DisplayName("пустая история → пустой repeated, без ошибок")
    void emptyHistory() throws Exception {
        when(runContextService.build(any(), any())).thenReturn(
                new RunContextView(List.of(), List.of(), List.of(), List.of()));

        assertEquals(0, callGetRunContext().getHistoryCount());
    }

    @Nested
    @DisplayName("getLlmCredentials — платформенный fallback")
    class GetLlmCredentialsFallback {

        private final UUID agentId = UUID.randomUUID();

        @Test
        @DisplayName("нет привязки + платформенный провайдер включён → креденшлы с default_model")
        void fallsBackToPlatformProvider() throws Exception {
            LlmProvider platform = LlmProvider.builder()
                    .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                    .name(LlmProviderService.PLATFORM_PROVIDER_NAME)
                    .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                    .baseUrl("https://openrouter.ai/api/v1")
                    .defaultModel("gpt-5-mini")
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdOrderByName(agentId)).thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform-key");

            LlmCredentials creds = callGetLlmCredentials(agentId);

            assertEquals("OPENAI_COMPATIBLE", creds.getProviderType());
            assertEquals("https://openrouter.ai/api/v1", creds.getBaseUrl());
            assertEquals("gpt-5-mini", creds.getModel());
            assertEquals("sk-platform-key", creds.getApiKey());
        }

        @Test
        @DisplayName("нет привязки и платформенный недоступен → NOT_FOUND")
        void notFoundWithoutBindingAndPlatform() {
            when(agentLlmRepository.findAllByAgentIdOrderByName(agentId)).thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.empty());

            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> callGetLlmCredentials(agentId));
            assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
        }

        @Test
        @DisplayName("личная привязка побеждает: платформенный провайдер не опрашивается")
        void userBindingWinsOverPlatform() throws Exception {
            UUID providerId = UUID.randomUUID();
            AgentLlm binding = AgentLlm.builder()
                    .agentId(agentId)
                    .llmProviderId(providerId)
                    .name("main_model")
                    .model("user-model")
                    .build();
            LlmProvider provider = LlmProvider.builder()
                    .providerType(LlmProviderType.OPENAI)
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdOrderByName(agentId)).thenReturn(List.of(binding));
            when(llmProviderRepository.findById(providerId)).thenReturn(Optional.of(provider));
            when(llmProviderService.decryptApiKey(provider)).thenReturn("sk-user-key");

            LlmCredentials creds = callGetLlmCredentials(agentId);

            assertEquals("user-model", creds.getModel());
            assertEquals("sk-user-key", creds.getApiKey());
            verify(llmProviderService, never()).findUsablePlatformProvider();
        }

        private LlmCredentials callGetLlmCredentials(UUID agent) throws Exception {
            GetLlmCredentialsRequest request = GetLlmCredentialsRequest.newBuilder()
                    .setAgentId(agent.toString())
                    .build();
            AtomicReference<LlmCredentials> response = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            StreamObserver<LlmCredentials> observer = new StreamObserver<>() {
                @Override
                public void onNext(LlmCredentials value) {
                    response.set(value);
                }

                @Override
                public void onError(Throwable t) {
                    error.set(t);
                }

                @Override
                public void onCompleted() {
                }
            };
            Context ctx = Context.current().withValue(
                    WorkerPoolContextHolder.CONTEXT_KEY, new WorkerPoolContext("pool-1", "worker-1"));
            ctx.call(() -> {
                service.getLlmCredentials(request, observer);
                return null;
            });
            if (error.get() instanceof StatusRuntimeException sre) {
                throw sre;
            }
            assertNull(error.get(), () -> "GetLlmCredentials failed: " + error.get());
            return response.get();
        }
    }

    private RunContext callGetRunContext() throws Exception {
        GetRunContextRequest request = GetRunContextRequest.newBuilder()
                .setAgentId(UUID.randomUUID().toString())
                .setTriggerId(UUID.randomUUID().toString())
                .build();
        AtomicReference<RunContext> response = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        StreamObserver<RunContext> observer = new StreamObserver<>() {
            @Override
            public void onNext(RunContext value) {
                response.set(value);
            }

            @Override
            public void onError(Throwable t) {
                error.set(t);
            }

            @Override
            public void onCompleted() {
            }
        };
        // WorkerPoolContextHolder.current() требует gRPC-контекст с аутентифицированным пулом.
        Context ctx = Context.current().withValue(
                WorkerPoolContextHolder.CONTEXT_KEY, new WorkerPoolContext("pool-1", "worker-1"));
        ctx.call(() -> {
            service.getRunContext(request, observer);
            return null;
        });
        assertNull(error.get(), () -> "GetRunContext failed: " + error.get());
        return response.get();
    }
}
