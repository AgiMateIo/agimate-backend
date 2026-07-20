package ru.agimate.controlapi.grpc.service;

import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.FileChunk;
import ru.agimate.agentworker.GetFileRequest;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.ReportLlmUsageRequest;
import ru.agimate.agentworker.ReportLlmUsageResponse;
import ru.agimate.agentworker.RunContext;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.enums.LlmProviderType;
import ru.agimate.controlapi.database.enums.LlmPurpose;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContext;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.LlmUsageService;
import ru.agimate.controlapi.service.SystemSkillBootstrap;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver;
import ru.agimate.controlapi.service.llm.LlmQuotaService;
import ru.agimate.controlapi.service.llm.QuotaExceededException;
import ru.agimate.controlapi.service.runcontext.InboundPart;
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
    private final ru.agimate.controlapi.database.repositories.LlmProviderModelRepository
            llmProviderModelRepository =
            mock(ru.agimate.controlapi.database.repositories.LlmProviderModelRepository.class);
    private final LlmProviderService llmProviderService = mock(LlmProviderService.class);
    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final LlmUsageService llmUsageService = mock(LlmUsageService.class);
    private final LlmQuotaService llmQuotaService = mock(LlmQuotaService.class);
    private final ru.agimate.controlapi.storage.FileStorageService fileStorageService =
            mock(ru.agimate.controlapi.storage.FileStorageService.class);
    // Резолвер — настоящий, поверх тех же моков: тесты остаются контрактными, через gRPC-поверхность.
    private final LlmCredentialsResolver llmCredentialsResolver = new LlmCredentialsResolver(
            agentLlmRepository,
            llmProviderRepository,
            llmProviderModelRepository,
            llmProviderService,
            llmQuotaService);
    private final AgentContextGrpcService service = new AgentContextGrpcService(
            runContextService,
            mock(ru.agimate.controlapi.service.trigger.RunActivityService.class),
            llmCredentialsResolver,
            agentRepository,
            llmUsageService,
            fileStorageService);

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
                        new RunHistoryMessage(ChannelSessionMessageKind.ERROR, "упс")),
                List.of(new InboundPart("agf_img", "image", "image/png", 4096, "shot.png")));
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

        assertEquals(1, response.getInboundPartsCount());
        assertEquals("agf_img", response.getInboundParts(0).getFileId());
        assertEquals("image", response.getInboundParts(0).getType());
        assertEquals("image/png", response.getInboundParts(0).getMime());
        assertEquals(4096, response.getInboundParts(0).getSize());
        assertEquals("shot.png", response.getInboundParts(0).getName());
    }

    @Test
    @DisplayName("пустая история → пустой repeated, без ошибок")
    void emptyHistory() throws Exception {
        when(runContextService.build(any(), any())).thenReturn(
                new RunContextView(List.of(), List.of(), List.of(), List.of(), List.of()));

        assertEquals(0, callGetRunContext().getHistoryCount());
    }

    @Nested
    @DisplayName("getLlmCredentials — платформенный fallback")
    class GetLlmCredentialsFallback {

        private final UUID agentId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();

        @org.junit.jupiter.api.BeforeEach
        void stubAgent() {
            Agent agent = new Agent();
            agent.setId(agentId);
            agent.setUserId(userId);
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        }

        @Test
        @DisplayName("нет привязки + платформенный провайдер включён → креденшлы с default_model")
        void fallsBackToPlatformProvider() throws Exception {
            LlmProvider platform = LlmProvider.builder()
                    .id(UUID.randomUUID())
                    .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                    .name(LlmProviderService.PLATFORM_PROVIDER_NAME)
                    .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                    .baseUrl("https://openrouter.ai/api/v1")
                    .defaultModel("gpt-5-mini")
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByName(agentId, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            when(llmProviderService.decryptApiKey(platform)).thenReturn("sk-platform-key");

            LlmCredentials creds = callGetLlmCredentials(agentId);

            assertEquals("OPENAI_COMPATIBLE", creds.getProviderType());
            assertEquals("https://openrouter.ai/api/v1", creds.getBaseUrl());
            assertEquals("gpt-5-mini", creds.getModel());
            assertEquals("sk-platform-key", creds.getApiKey());
            assertEquals(platform.getId().toString(), creds.getProviderId());
        }

        @Test
        @DisplayName("нет привязки и платформенный недоступен → NOT_FOUND")
        void notFoundWithoutBindingAndPlatform() {
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByName(agentId, LlmPurpose.CHAT))
                    .thenReturn(List.of());
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
                    .id(providerId)
                    .providerType(LlmProviderType.OPENAI)
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByName(agentId, LlmPurpose.CHAT))
                    .thenReturn(List.of(binding));
            when(llmProviderRepository.findById(providerId)).thenReturn(Optional.of(provider));
            when(llmProviderService.decryptApiKey(provider)).thenReturn("sk-user-key");

            LlmCredentials creds = callGetLlmCredentials(agentId);

            assertEquals("user-model", creds.getModel());
            assertEquals("sk-user-key", creds.getApiKey());
            verify(llmProviderService, never()).findUsablePlatformProvider();
        }

        @Test
        @DisplayName("квота исчерпана → RESOURCE_EXHAUSTED с человекочитаемым текстом")
        void quotaExceededMapsToResourceExhausted() {
            LlmProvider platform = LlmProvider.builder()
                    .id(UUID.randomUUID())
                    .userId(SystemSkillBootstrap.SYSTEM_USER_ID)
                    .name(LlmProviderService.PLATFORM_PROVIDER_NAME)
                    .providerType(LlmProviderType.OPENAI_COMPATIBLE)
                    .baseUrl("https://openrouter.ai/api/v1")
                    .defaultModel("gpt-5-mini")
                    .enabled(true)
                    .build();
            when(agentLlmRepository.findAllByAgentIdAndPurposeOrderByName(agentId, LlmPurpose.CHAT))
                    .thenReturn(List.of());
            when(llmProviderService.findUsablePlatformProvider()).thenReturn(Optional.of(platform));
            org.mockito.Mockito.doThrow(new QuotaExceededException("Дневной лимит исчерпан"))
                    .when(llmQuotaService).check(platform, userId, agentId);

            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> callGetLlmCredentials(agentId));

            assertEquals(Status.Code.RESOURCE_EXHAUSTED, error.getStatus().getCode());
            assertEquals("Дневной лимит исчерпан", error.getStatus().getDescription());
            verify(llmProviderService, never()).decryptApiKey(any());
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

    @Nested
    @DisplayName("reportLlmUsage — учёт расхода")
    class ReportUsage {

        private final UUID agentId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();
        private final UUID providerId = UUID.randomUUID();
        private final UUID runId = UUID.randomUUID();

        @Test
        @DisplayName("happy path: user_id берётся у агента, кэш-нули превращаются в NULL")
        void recordsUsage() throws Exception {
            Agent agent = new Agent();
            agent.setId(agentId);
            agent.setUserId(userId);
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
            when(llmUsageService.record(any())).thenReturn(false);

            ReportLlmUsageResponse response = callReportUsage(ReportLlmUsageRequest.newBuilder()
                    .setCallId("wf-llm-1")
                    .setAgentId(agentId.toString())
                    .setRunId(runId.toString())
                    .setProviderId(providerId.toString())
                    .setModel("gpt-5-mini")
                    .setInputTokens(100)
                    .setOutputTokens(20)
                    .setCacheReadTokens(50)
                    .setCacheWriteTokens(0)
                    .build());

            assertEquals(false, response.getDuplicate());
            var captor = org.mockito.ArgumentCaptor.forClass(LlmUsageService.UsageReport.class);
            verify(llmUsageService).record(captor.capture());
            LlmUsageService.UsageReport report = captor.getValue();
            assertEquals("wf-llm-1", report.callId());
            assertEquals(runId, report.runId());
            assertEquals(userId, report.userId());
            assertEquals(providerId, report.providerId());
            assertEquals(100, report.inputTokens());
            assertEquals(20, report.outputTokens());
            assertEquals(50, report.cacheReadTokens());
            assertNull(report.cacheWriteTokens(), "0 в proto3 = «не прислано» → NULL");
        }

        @Test
        @DisplayName("дубликат: duplicate=true доезжает до ответа")
        void duplicateFlag() throws Exception {
            Agent agent = new Agent();
            agent.setId(agentId);
            agent.setUserId(userId);
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
            when(llmUsageService.record(any())).thenReturn(true);

            ReportLlmUsageResponse response = callReportUsage(validRequest().build());
            assertEquals(true, response.getDuplicate());
        }

        @Test
        @DisplayName("неизвестный агент → NOT_FOUND, пустой call_id → INVALID_ARGUMENT")
        void validation() {
            when(agentRepository.findById(agentId)).thenReturn(Optional.empty());
            StatusRuntimeException notFound = assertThrows(StatusRuntimeException.class,
                    () -> callReportUsage(validRequest().build()));
            assertEquals(Status.Code.NOT_FOUND, notFound.getStatus().getCode());

            StatusRuntimeException invalid = assertThrows(StatusRuntimeException.class,
                    () -> callReportUsage(validRequest().setCallId("").build()));
            assertEquals(Status.Code.INVALID_ARGUMENT, invalid.getStatus().getCode());
        }

        private ReportLlmUsageRequest.Builder validRequest() {
            return ReportLlmUsageRequest.newBuilder()
                    .setCallId("wf-llm-1")
                    .setAgentId(agentId.toString())
                    .setProviderId(providerId.toString())
                    .setModel("gpt-5-mini")
                    .setInputTokens(1)
                    .setOutputTokens(1);
        }

        private ReportLlmUsageResponse callReportUsage(ReportLlmUsageRequest request) throws Exception {
            AtomicReference<ReportLlmUsageResponse> response = new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            StreamObserver<ReportLlmUsageResponse> observer = new StreamObserver<>() {
                @Override
                public void onNext(ReportLlmUsageResponse value) {
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
                service.reportLlmUsage(request, observer);
                return null;
            });
            if (error.get() instanceof StatusRuntimeException sre) {
                throw sre;
            }
            assertNull(error.get(), () -> "ReportLlmUsage failed: " + error.get());
            return response.get();
        }
    }

    @Nested
    @DisplayName("getFile — стрим содержимого вложения")
    class GetFile {

        private final UUID agentId = UUID.randomUUID();
        private final UUID userId = UUID.randomUUID();

        private void stubAgent() {
            Agent agent = new Agent();
            agent.setId(agentId);
            agent.setUserId(userId);
            when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        }

        @Test
        @DisplayName("отдаёт байты чанками; первый чанк несёт mime и total_size")
        void streamsChunks() throws Exception {
            stubAgent();
            byte[] content = new byte[200_000]; // > 128 KB чанка → минимум два чанка
            for (int i = 0; i < content.length; i++) {
                content[i] = (byte) (i % 7);
            }
            ru.agimate.controlapi.database.entities.StoredFile file =
                    ru.agimate.controlapi.database.entities.StoredFile.builder()
                            .id(UUID.randomUUID()).userId(userId).mime("image/png")
                            .sizeBytes((long) content.length).build();
            when(fileStorageService.open(userId, "agf_x")).thenReturn(
                    new ru.agimate.controlapi.storage.FileStorageService.FileContent(
                            file, new java.io.ByteArrayInputStream(content)));

            List<FileChunk> chunks = callGetFile("agf_x");

            assertTrue(chunks.size() >= 2, "ожидали несколько чанков");
            assertEquals("image/png", chunks.get(0).getMime());
            assertEquals(content.length, chunks.get(0).getTotalSize());
            java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
            chunks.forEach(c -> all.writeBytes(c.getData().toByteArray()));
            assertEquals(content.length, all.size());
        }

        @Test
        @DisplayName("недоступный файл (чужой/протух) → NOT_FOUND")
        void notFound() {
            stubAgent();
            when(fileStorageService.open(userId, "agf_x"))
                    .thenThrow(new ru.agimate.controlapi.storage.StoredFileNotFoundException("agf_x"));

            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> callGetFile("agf_x"));
            assertEquals(Status.Code.NOT_FOUND, error.getStatus().getCode());
        }

        @Test
        @DisplayName("пустой file_id → INVALID_ARGUMENT")
        void blankFileId() {
            stubAgent();
            StatusRuntimeException error = assertThrows(StatusRuntimeException.class,
                    () -> callGetFile(""));
            assertEquals(Status.Code.INVALID_ARGUMENT, error.getStatus().getCode());
        }

        private List<FileChunk> callGetFile(String fileId) throws Exception {
            GetFileRequest request = GetFileRequest.newBuilder()
                    .setAgentId(agentId.toString()).setFileId(fileId).build();
            List<FileChunk> chunks = new java.util.ArrayList<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            StreamObserver<FileChunk> observer = new StreamObserver<>() {
                @Override
                public void onNext(FileChunk value) {
                    chunks.add(value);
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
                service.getFile(request, observer);
                return null;
            });
            if (error.get() instanceof StatusRuntimeException sre) {
                throw sre;
            }
            assertNull(error.get(), () -> "GetFile failed: " + error.get());
            return chunks;
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
