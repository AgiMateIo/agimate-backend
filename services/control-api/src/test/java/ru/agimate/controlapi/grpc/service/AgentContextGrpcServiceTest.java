package ru.agimate.controlapi.grpc.service;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.RunContext;
import ru.agimate.controlapi.connectors.core.dto.ConnectorToolSpec;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContext;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.runcontext.RunBlock;
import ru.agimate.controlapi.service.runcontext.RunContextService;
import ru.agimate.controlapi.service.runcontext.RunContextView;
import ru.agimate.controlapi.service.runcontext.RunHistoryMessage;
import ru.agimate.controlapi.service.runcontext.RunTool;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Контрактный тест gRPC-маппинга GetRunContext: каждая коллекция {@link RunContextView} обязана
 * доехать до proto-ответа целиком. Появился после бага B1 — история собиралась сервисом, но
 * не добавлялась в RunContext, и ни один тест по обе стороны шва этого не видел.
 */
@DisplayName("AgentContextGrpcService — маппинг RunContextView → proto RunContext")
class AgentContextGrpcServiceTest {

    private final RunContextService runContextService = mock(RunContextService.class);
    private final AgentContextGrpcService service = new AgentContextGrpcService(
            runContextService,
            mock(AgentLlmRepository.class),
            mock(LlmProviderRepository.class),
            mock(LlmProviderService.class));

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
