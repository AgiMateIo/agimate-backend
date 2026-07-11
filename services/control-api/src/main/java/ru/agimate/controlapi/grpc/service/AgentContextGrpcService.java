package ru.agimate.controlapi.grpc.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.AgentLlm;
import ru.agimate.controlapi.database.entities.LlmProvider;
import ru.agimate.controlapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.controlapi.database.repositories.AgentLlmRepository;
import ru.agimate.controlapi.database.repositories.LlmProviderRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.LlmProviderService;
import ru.agimate.controlapi.service.runcontext.RunBlock;
import ru.agimate.controlapi.service.runcontext.RunContextService;
import ru.agimate.controlapi.service.runcontext.RunContextView;
import ru.agimate.controlapi.service.runcontext.RunHistoryMessage;
import ru.agimate.controlapi.service.runcontext.RunTool;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.ConnectorToolSpec;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.agentworker.PromptBlock;
import ru.agimate.agentworker.RunContext;
import ru.agimate.agentworker.ToolAnnotations;

import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.nullToEmpty;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.toJsonBytes;

/**
 * Read-поверхность протокола воркера: {@code GetRunContext} (весь контекст рана одним вызовом,
 * сборка — {@link RunContextService}) и {@code GetLlmCredentials} (отдельно: результат
 * GetRunContext чекпоинтится воркером, api_key в чекпоинт попадать не должен).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class AgentContextGrpcService extends AgentContextGrpc.AgentContextImplBase {

    private final RunContextService runContextService;
    private final AgentLlmRepository agentLlmRepository;
    private final LlmProviderRepository llmProviderRepository;
    private final LlmProviderService llmProviderService;

    @Override
    public void getRunContext(GetRunContextRequest request, StreamObserver<RunContext> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID triggerId = parseUuid(request.getTriggerId(), "trigger_id");

            RunContextView view = runContextService.build(agentId, triggerId);

            RunContext.Builder builder = RunContext.newBuilder();
            view.systemBlocks().forEach(b -> builder.addSystemBlocks(toProto(b)));
            view.userBlocks().forEach(b -> builder.addUserBlocks(toProto(b)));
            view.tools().forEach(t -> builder.addTools(toProto(t)));
            view.history().forEach(h -> builder.addHistory(toProto(h)));

            log.debug("issued RunContext pool={} agent={} trigger={}", poolId, agentId, triggerId);
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentContext.GetRunContext failed pool={} agent={} trigger={}",
                    poolId, request.getAgentId(), request.getTriggerId(), e);
            handleError(e, responseObserver);
        }
    }

    private static HistoryMessage toProto(RunHistoryMessage message) {
        return HistoryMessage.newBuilder()
                .setKind(toProto(message.kind()))
                .setText(nullToEmpty(message.text()))
                .build();
    }

    private static MessageKind toProto(ChannelSessionMessageKind kind) {
        return switch (kind) {
            // Дореформенные kinds сервис уже маппит на v2; ветки здесь — на случай пропуска.
            case INBOUND, REQUEST -> MessageKind.MESSAGE_KIND_INBOUND;
            case PROGRESS -> MessageKind.MESSAGE_KIND_PROGRESS;
            case ANSWER, RESPONSE -> MessageKind.MESSAGE_KIND_ANSWER;
            case ERROR -> MessageKind.MESSAGE_KIND_ERROR;
        };
    }

    private static PromptBlock toProto(RunBlock block) {
        return PromptBlock.newBuilder()
                .setName(nullToEmpty(block.name()))
                .setSource(nullToEmpty(block.source()))
                .setContent(nullToEmpty(block.content()))
                .putAllAttrs(block.attrs())
                .setTrusted(block.trusted())
                .setEphemeral(block.ephemeral())
                .build();
    }

    private static ConnectorToolSpec toProto(RunTool tool) {
        var spec = tool.spec();
        ConnectorToolSpec.Builder builder = ConnectorToolSpec.newBuilder()
                .setName(nullToEmpty(spec.name()))
                .setConnectionId(nullToEmpty(tool.connectionId()))
                .setNamespace(nullToEmpty(tool.namespace()))
                .setConnectorCode(nullToEmpty(tool.connectorCode()));
        if (spec.title() != null) {
            builder.setTitle(spec.title());
        }
        if (spec.description() != null) {
            builder.setDescription(spec.description());
        }
        if (spec.inputSchema() != null) {
            builder.setInputSchema(toJsonBytes(spec.inputSchema()));
        }
        if (spec.outputSchema() != null) {
            builder.setOutputSchema(toJsonBytes(spec.outputSchema()));
        }
        var annotations = spec.annotations() != null
                ? spec.annotations()
                : ru.agimate.controlapi.connectors.core.dto.ToolAnnotationsSpec.DEFAULT;
        builder.setAnnotations(ToolAnnotations.newBuilder()
                .setReadOnlyHint(annotations.readOnlyHint())
                .setDestructiveHint(annotations.destructiveHint())
                .setIdempotentHint(annotations.idempotentHint())
                .setOpenWorldHint(annotations.openWorldHint())
                .build());
        if (spec.meta() != null) {
            builder.putAllMeta(spec.meta());
        }
        return builder.build();
    }

    @Override
    public void getLlmCredentials(GetLlmCredentialsRequest request, StreamObserver<LlmCredentials> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            AgentLlm llmBinding = agentLlmRepository.findAllByAgentIdOrderByName(agentId).stream()
                    .findFirst()
                    .orElseThrow(() -> new NotFoundStatusException(
                            "No LLM binding for agent: " + agentId));
            LlmProvider provider = llmProviderRepository.findById(llmBinding.getLlmProviderId())
                    .orElseThrow(() -> new NotFoundStatusException(
                            "LLM provider not found: " + llmBinding.getLlmProviderId()));
            if (!provider.isEnabled()) {
                responseObserver.onError(Status.FAILED_PRECONDITION
                        .withDescription("LLM provider disabled").asRuntimeException());
                return;
            }

            String apiKey = llmProviderService.decryptApiKey(provider);

            LlmCredentials response = LlmCredentials.newBuilder()
                    .setProviderType(provider.getProviderType().name())
                    .setBaseUrl(nullToEmpty(provider.getBaseUrl()))
                    .setApiKey(apiKey)
                    .setModel(nullToEmpty(llmBinding.getModel()))
                    .build();
            log.info("issued LLM credentials pool={} agent={} providerType={}",
                    WorkerPoolContextHolder.current().poolId(), agentId, provider.getProviderType());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver);
        }
    }
}
