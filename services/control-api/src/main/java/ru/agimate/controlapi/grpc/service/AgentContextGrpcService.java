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
import ru.agimate.controlapi.storage.FileStorageService;
import ru.agimate.controlapi.storage.StoredFileNotFoundException;
import ru.agimate.agentworker.AgentContextGrpc;
import ru.agimate.agentworker.FileChunk;
import ru.agimate.agentworker.GetFileRequest;
import ru.agimate.agentworker.GetLlmCredentialsRequest;
import ru.agimate.agentworker.GetRunContextRequest;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.ReportLlmUsageRequest;
import ru.agimate.agentworker.ReportLlmUsageResponse;
import ru.agimate.agentworker.RunContext;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.nullToEmpty;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseOptionalUuid;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

/**
 * Поверхность протокола воркера: {@code GetRunContext} (весь контекст рана одним вызовом,
 * сборка — {@link RunContextService}), {@code GetLlmCredentials} (отдельно: результат
 * GetRunContext чекпоинтится воркером, api_key в чекпоинт попадать не должен),
 * {@code GetFile} (содержимое inbound-вложения чанками — как api_key, тянется inline и в
 * чекпоинт не попадает) и {@code ReportLlmUsage} (учёт расхода токенов).
 *
 * <p>Транзакции — на методах, НЕ на классе: {@code ReportLlmUsage} пишет, и классовый
 * {@code readOnly = true} заворачивал бы его INSERT'ы в read-only транзакцию (внутренний
 * {@code @Transactional} сервиса присоединяется к внешней и readOnly не сбрасывает).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentContextGrpcService extends AgentContextGrpc.AgentContextImplBase {

    private final RunContextService runContextService;
    private final RunActivityService runActivityService;
    private final LlmCredentialsResolver llmCredentialsResolver;
    private final AgentRepository agentRepository;
    private final LlmUsageService llmUsageService;
    private final FileStorageService fileStorageService;

    /** Размер чанка содержимого файла: << дефолтный 4 MB предел gRPC-сообщения. */
    private static final int FILE_CHUNK_BYTES = 128 * 1024;

    @Override
    @Transactional(readOnly = true)
    public void getRunContext(GetRunContextRequest request, StreamObserver<RunContext> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID triggerId = parseUuid(request.getTriggerId(), "trigger_id");
            runActivityService.touch(triggerId);

            RunContextView view = runContextService.build(agentId, triggerId);

            RunContext.Builder builder = RunContext.newBuilder();
            view.systemBlocks().forEach(b -> builder.addSystemBlocks(RunContextMapper.toProto(b)));
            view.userBlocks().forEach(b -> builder.addUserBlocks(RunContextMapper.toProto(b)));
            view.tools().forEach(t -> builder.addTools(RunContextMapper.toProto(t)));
            view.history().forEach(h -> builder.addHistory(RunContextMapper.toProto(h)));
            view.inboundParts().forEach(p -> builder.addInboundParts(RunContextMapper.toProto(p)));

            log.debug("issued RunContext pool={} agent={} trigger={}", poolId, agentId, triggerId);
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            handleError(e, responseObserver, "GetRunContext pool=" + poolId
                    + " agent=" + request.getAgentId() + " trigger=" + request.getTriggerId());
        }
    }

    /**
     * Содержимое inbound-вложения чанками. НЕ {@code @Transactional}: держать DB-соединение на всё
     * время стрима байтов нельзя; ownership-гейт (file.user_id == agent.user_id) — через
     * {@link FileStorageService#findReadable}. Первый чанк несёт mime и total_size.
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
                // Владение/просрочка/незавершённость неразличимы — файл просто недоступен воркеру.
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
                    // Пустой файл (0 байт READY не бывает — store отвергает size<=0, но на всякий).
                    responseObserver.onNext(FileChunk.newBuilder().setMime(mime).setTotalSize(total).build());
                }
            }
            // Диагностика: mime заявленный vs сигнатура содержимого + факт полной выгрузки.
            log.info("streamed file {} pool={} agent={} mime={} signature={} declared={} streamed={}",
                    fileId, poolId, agentId, mime, signature, total, streamed);
            if (streamed != total) {
                log.warn("file {} size mismatch: declared={} streamed={} — блоб усечён/битый",
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

    /** proto3 не различает 0 и «не прислано» — нулевые кэш-метрики храним как NULL. */
    private static Integer zeroToNull(int value) {
        return value == 0 ? null : value;
    }

    /**
     * Итоговый extra_body резолвера в проводной формат. Пустая map → пустая строка
     * («нет доп. полей» — то же видит воркер от старого control-api при rolling deploy).
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
     * Метка формата по магическим байтам заголовка (не содержимое, только сигнатура) — для
     * диагностики: заявленный mime {@code image/jpeg} vs реальные байты. {@code unknown} при
     * несовпадении = битый/не-тот файл; расхождение с mime = вероятная причина «модель не видит».
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
