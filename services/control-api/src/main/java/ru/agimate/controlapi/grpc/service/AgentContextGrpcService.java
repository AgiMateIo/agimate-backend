package ru.agimate.controlapi.grpc.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.grpc.mapper.RunContextMapper;
import ru.agimate.controlapi.service.LlmUsageService;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver;
import ru.agimate.controlapi.service.llm.LlmCredentialsResolver.ResolvedLlm;
import ru.agimate.controlapi.service.runcontext.RunContextService;
import ru.agimate.controlapi.service.runcontext.RunContextView;
import ru.agimate.controlapi.service.trigger.RunActivityService;
import ru.agimate.controlapi.service.trigger.SteeringService;
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.ClaimSteeringRequest;
import ru.agimate.agentworker.ClaimSteeringResponse;
import ru.agimate.agentworker.FileChunk;
import ru.agimate.agentworker.GetFileRequest;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.MarkSteeredRequest;
import ru.agimate.agentworker.MarkSteeredResponse;
import ru.agimate.agentworker.ReportLlmUsageRequest;
import ru.agimate.agentworker.ReportLlmUsageResponse;
import ru.agimate.agentworker.RunContext;
import ru.agimate.agentworker.SteeringMessage;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.nullToEmpty;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseOptionalUuid;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

/**
 * The worker protocol's surface: {@code GetRunContext} (the whole run context in one call, assembled by
 * {@link RunContextService}), {@code GetLlmCredentials} (separate: the GetRunContext result is
 * checkpointed by the worker, and api_key must never enter a checkpoint), {@code GetFile} (the contents
 * of an inbound attachment in chunks — like api_key, pulled inline and never checkpointed),
 * {@code ReportLlmUsage} (token usage accounting) and the steering pair
 * {@code ClaimSteering}/{@code MarkSteered} (the loop seam absorbing queued messages of the session).
 *
 * <p>Transactions are on the methods, NOT on the class: {@code ReportLlmUsage} writes, and a class-level
 * {@code readOnly = true} would wrap its INSERTs in a read-only transaction (the service's inner
 * {@code @Transactional} joins the outer one and does not clear readOnly).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentContextGrpcService extends AgentContextGrpc.AgentContextImplBase {

    private final RunContextService runContextService;
    private final RunActivityService runActivityService;
    private final SteeringService steeringService;
    private final LlmCredentialsResolver llmCredentialsResolver;
    private final AgentRepository agentRepository;
    private final LlmUsageService llmUsageService;
    private final FileStorageService fileStorageService;

    /** Chunk size of a file's contents: << gRPC's default 4 MB message limit. */
    private static final int FILE_CHUNK_BYTES = 128 * 1024;

    @Override
    @Transactional(readOnly = true)
    public void getRunContext(GetRunContextRequest request, StreamObserver<RunContext> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");
            runActivityService.touch(runId);

            RunContextView view = runContextService.build(agentId, runId);

            RunContext.Builder builder = RunContext.newBuilder();
            view.systemBlocks().forEach(b -> builder.addSystemBlocks(RunContextMapper.toProto(b)));
            view.userBlocks().forEach(b -> builder.addUserBlocks(RunContextMapper.toProto(b)));
            view.tools().forEach(t -> builder.addTools(RunContextMapper.toProto(t)));
            view.history().forEach(h -> builder.addHistory(RunContextMapper.toProto(h)));
            view.inboundParts().forEach(p -> builder.addInboundParts(RunContextMapper.toProto(p)));

            log.debug("issued RunContext pool={} agent={} run={}", poolId, agentId, runId);
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "GetRunContext pool=" + poolId
                    + " agent=" + request.getAgentId() + " run=" + request.getRunId());
        }
    }

    /**
     * Steering claim from the running run's seam: atomically takes the younger ENQUEUED runs of the
     * session ({@link SteeringService#claim}) and hands their inbound messages over. Not a durable
     * step on the worker — best-effort and idempotent for the same main. No {@code @Transactional}
     * here: the service owns its writing transaction.
     */
    @Override
    public void claimSteering(ClaimSteeringRequest request, StreamObserver<ClaimSteeringResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");
            runActivityService.touch(runId);

            List<SteeringService.SteeringInbound> claimed = steeringService.claim(agentId, runId);

            ClaimSteeringResponse.Builder builder = ClaimSteeringResponse.newBuilder();
            for (SteeringService.SteeringInbound inbound : claimed) {
                SteeringMessage.Builder message = SteeringMessage.newBuilder()
                        .setRunId(inbound.runId().toString())
                        .setText(nullToEmpty(inbound.text()));
                inbound.parts().forEach(p -> message.addParts(RunContextMapper.toProto(p)));
                builder.addMessages(message);
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "ClaimSteering agent=" + request.getAgentId()
                    + " run=" + request.getRunId());
        }
    }

    /** Absorption confirmed: the model has seen the claimed messages — {@link SteeringService#markSteered}. */
    @Override
    public void markSteered(MarkSteeredRequest request, StreamObserver<MarkSteeredResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");
            List<UUID> steeredRunIds = request.getSteeredRunIdsList().stream()
                    .map(id -> parseUuid(id, "steered_run_ids"))
                    .toList();

            int confirmed = steeringService.markSteered(agentId, runId, steeredRunIds);

            responseObserver.onNext(MarkSteeredResponse.newBuilder().setConfirmed(confirmed).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "MarkSteered agent=" + request.getAgentId()
                    + " run=" + request.getRunId());
        }
    }

    /**
     * The contents of an inbound attachment in chunks. NOT {@code @Transactional}: a database connection
     * must not be held for the whole byte stream; the ownership gate (file.user_id == agent.user_id)
     * goes through {@link FileStorageService#findReadable}. The first chunk carries mime and total_size.
     */
    @Override
    public void getFile(GetFileRequest request, StreamObserver<FileChunk> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            String fileId = request.getFileId();
            if (fileId.isBlank()) {
                throw Status.INVALID_ARGUMENT.withDescription("file_id is required").asRuntimeException();
            }
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));

            FileStorageService.FileContent content;
            try {
                content = fileStorageService.open(agent.getUserId(), fileId);
            } catch (StoredFileNotFoundException e) {
                // Ownership, expiry and incompleteness are indistinguishable — the file is simply unavailable to the worker.
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("file not available").asRuntimeException());
                return;
            }

            long total = content.file().getSizeBytes();
            String mime = nullToEmpty(content.file().getMime());
            long streamed = 0;
            String signature = null;
            try (InputStream in = content.content()) {
                byte[] buf = new byte[FILE_CHUNK_BYTES];
                int read;
                boolean first = true;
                while ((read = in.read(buf)) != -1) {
                    if (signature == null && read > 0) {
                        signature = imageSignature(buf, read);
                    }
                    streamed += read;
                    FileChunk.Builder chunk = FileChunk.newBuilder()
                            .setData(ByteString.copyFrom(buf, 0, read));
                    if (first) {
                        chunk.setMime(mime).setTotalSize(total);
                        first = false;
                    }
                    responseObserver.onNext(chunk.build());
                }
                if (first) {
                    // An empty file (a 0-byte READY does not happen — store rejects size<=0, but just in case).
                    responseObserver.onNext(FileChunk.newBuilder().setMime(mime).setTotalSize(total).build());
                }
            }
            // Diagnostics: the declared mime vs the content's signature, plus the fact of a complete upload.
            log.info("streamed file {} pool={} agent={} mime={} signature={} declared={} streamed={}",
                    fileId, poolId, agentId, mime, signature, total, streamed);
            if (streamed != total) {
                log.warn("file {} size mismatch: declared={} streamed={} — blob truncated or corrupt",
                        fileId, total, streamed);
            }
            responseObserver.onCompleted();
        } catch (IOException e) {
            handleError(new IllegalStateException("failed to stream file", e), responseObserver,
                    "GetFile pool=" + poolId + " agent=" + request.getAgentId());
        } catch (Exception e) {
            handleError(e, responseObserver, "GetFile pool=" + poolId
                    + " agent=" + request.getAgentId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void getLlmCredentials(GetLlmCredentialsRequest request, StreamObserver<LlmCredentials> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));
            ResolvedLlm resolved = llmCredentialsResolver.resolveChat(agentId, agent.getUserId());

            LlmCredentials response = LlmCredentials.newBuilder()
                    .setProviderType(resolved.provider().getProviderType().name())
                    .setBaseUrl(nullToEmpty(resolved.provider().getBaseUrl()))
                    .setApiKey(resolved.apiKey())
                    .setModel(nullToEmpty(resolved.model()))
                    .setProviderId(resolved.provider().getId().toString())
                    .setExtraBodyJson(toExtraBodyJson(resolved))
                    .addAllInputModalities(resolved.inputModalities())
                    .build();
            log.info("issued LLM credentials pool={} agent={} providerType={} platform={}",
                    WorkerPoolContextHolder.current().poolId(), agentId,
                    resolved.provider().getProviderType(), resolved.platformFallback());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "GetLlmCredentials agent=" + request.getAgentId());
        }
    }

    @Override
    public void reportLlmUsage(ReportLlmUsageRequest request, StreamObserver<ReportLlmUsageResponse> responseObserver) {
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID providerId = parseUuid(request.getProviderId(), "provider_id");
            UUID runId = parseOptionalUuid(request.getRunId(), "run_id");
            if (request.getCallId().isBlank()) {
                throw Status.INVALID_ARGUMENT.withDescription("call_id is required").asRuntimeException();
            }
            Agent agent = agentRepository.findById(agentId)
                    .orElseThrow(() -> new NotFoundStatusException("Agent not found: " + agentId));

            boolean duplicate = llmUsageService.record(new LlmUsageService.UsageReport(
                    request.getCallId(), runId, agentId, agent.getUserId(), providerId,
                    request.getModel(),
                    request.getInputTokens(), request.getOutputTokens(),
                    zeroToNull(request.getCacheReadTokens()), zeroToNull(request.getCacheWriteTokens())));

            responseObserver.onNext(ReportLlmUsageResponse.newBuilder().setDuplicate(duplicate).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "ReportLlmUsage agent=" + request.getAgentId());
        }
    }

    /** proto3 does not distinguish 0 from «not sent» — so zero cache metrics are stored as NULL. */
    private static Integer zeroToNull(int value) {
        return value == 0 ? null : value;
    }

    /**
     * The resolver's final extra_body in wire format. An empty map → an empty string («no extra fields»
     * — the same thing the worker sees from an older control-api during a rolling deploy).
     */
    private String toExtraBodyJson(ResolvedLlm resolved) {
        if (resolved.extraBody().isEmpty()) {
            return "";
        }
        return JsonUtils.toJson(resolved.extraBody()).orElseGet(() -> {
            log.error("extra_body for provider {} model {} is not serializable — sending none",
                    resolved.provider().getId(), resolved.model());
            return "";
        });
    }

    /**
     * A format marker from the header's magic bytes (not the contents, only the signature) — for
     * diagnostics: the declared mime {@code image/jpeg} vs the actual bytes. {@code unknown} on a
     * mismatch means a corrupt or wrong file; a divergence from the mime is the likely reason for «the
     * model cannot see it».
     */
    private static String imageSignature(byte[] buf, int len) {
        if (len >= 3 && (buf[0] & 0xFF) == 0xFF && (buf[1] & 0xFF) == 0xD8 && (buf[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (len >= 8 && (buf[0] & 0xFF) == 0x89 && buf[1] == 'P' && buf[2] == 'N' && buf[3] == 'G') {
            return "png";
        }
        if (len >= 6 && buf[0] == 'G' && buf[1] == 'I' && buf[2] == 'F') {
            return "gif";
        }
        if (len >= 12 && buf[0] == 'R' && buf[1] == 'I' && buf[2] == 'F' && buf[3] == 'F'
                && buf[8] == 'W' && buf[9] == 'E' && buf[10] == 'B' && buf[11] == 'P') {
            return "webp";
        }
        return "unknown";
    }
}
