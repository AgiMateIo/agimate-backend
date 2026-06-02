package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.agimate.agentworker.ActiveRun;
import ru.agimate.agentworker.AgentRunRegistryGrpc;
import ru.agimate.agentworker.GetActiveRunRequest;
import ru.agimate.agentworker.GetActiveRunResponse;
import ru.agimate.agentworker.RegisterRunRequest;
import ru.agimate.agentworker.RegisterRunResponse;
import ru.agimate.agentworker.ReleaseRunRequest;
import ru.agimate.agentworker.ReleaseRunResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.deviceapi.service.channel.AgentRunRegistryService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunRegistryGrpcService extends AgentRunRegistryGrpc.AgentRunRegistryImplBase {

    private final AgentRunRegistryService agentRunRegistryService;

    @Override
    public void registerRun(RegisterRunRequest request, StreamObserver<RegisterRunResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID sessionPubId = parseUuid(request.getSessionPubId(), "session_pub_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");

            AgentRunRegistryService.ActiveRunView view =
                    agentRunRegistryService.registerRun(sessionPubId, runId, request.getTtlSeconds());

            log.debug("AgentRunRegistry.RegisterRun pool={} session={} run={}", poolId, sessionPubId, runId);
            responseObserver.onNext(RegisterRunResponse.newBuilder()
                    .setActiveRun(toProto(view))
                    .build());
            responseObserver.onCompleted();
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (DataIntegrityViolationException e) {
            // Partial unique index tripped: another run already holds this session's slot.
            responseObserver.onError(Status.ABORTED
                    .withDescription("Another active run holds this session; INTERRUPT must release it first")
                    .asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("AgentRunRegistry.RegisterRun failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getActiveRun(GetActiveRunRequest request, StreamObserver<GetActiveRunResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID sessionPubId = parseUuid(request.getSessionPubId(), "session_pub_id");

            Optional<AgentRunRegistryService.ActiveRunView> view =
                    agentRunRegistryService.getActiveRun(sessionPubId);

            GetActiveRunResponse.Builder builder = GetActiveRunResponse.newBuilder()
                    .setActive(view.isPresent());
            view.ifPresent(v -> builder.setActiveRun(toProto(v)));

            log.debug("AgentRunRegistry.GetActiveRun pool={} session={} active={}",
                    poolId, sessionPubId, view.isPresent());
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("AgentRunRegistry.GetActiveRun failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void releaseRun(ReleaseRunRequest request, StreamObserver<ReleaseRunResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID runId = parseUuid(request.getRunId(), "run_id");

            boolean released = agentRunRegistryService.releaseRun(runId);

            log.debug("AgentRunRegistry.ReleaseRun pool={} run={} released={}", poolId, runId, released);
            responseObserver.onNext(ReleaseRunResponse.newBuilder().setReleased(released).build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("AgentRunRegistry.ReleaseRun failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private static ActiveRun toProto(AgentRunRegistryService.ActiveRunView view) {
        ActiveRun.Builder b = ActiveRun.newBuilder()
                .setRunId(view.runId().toString());
        if (view.agentId() != null) {
            b.setAgentPubId(view.agentId().toString());
        }
        if (view.sessionId() != null) {
            b.setSessionPubId(view.sessionId().toString());
        }
        if (view.acquiredAt() != null) {
            b.setAcquiredAt(toProtoTimestamp(view.acquiredAt()));
        }
        if (view.expiresAt() != null) {
            b.setExpiresAt(toProtoTimestamp(view.expiresAt()));
        }
        return b.build();
    }

    private static Timestamp toProtoTimestamp(LocalDateTime ldt) {
        var instant = ldt.toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " is required").asRuntimeException();
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " is not a valid UUID").asRuntimeException();
        }
    }
}
