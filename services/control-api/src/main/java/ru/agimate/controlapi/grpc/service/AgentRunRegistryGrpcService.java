package ru.agimate.controlapi.grpc.service;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import ru.agimate.agentworker.AgentRunRegistryGrpc;
import ru.agimate.agentworker.RegisterRunRequest;
import ru.agimate.agentworker.RegisterRunResponse;
import ru.agimate.agentworker.ReleaseRunRequest;
import ru.agimate.agentworker.ReleaseRunResponse;
import ru.agimate.agentworker.RunSlotStatus;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.service.channel.AgentRunRegistryService;
import ru.agimate.controlapi.service.channel.AgentRunRegistryService.RegisterResult;
import ru.agimate.controlapi.service.channel.AgentRunRegistryService.SlotStatus;

import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.handleError;
import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

/**
 * Active-run registry (протокол v2): воркер оперирует только {@code trigger_id}, сессию
 * single-writer'а резолвит бэк и возвращает как {@code session_key} (партиционный ключ очереди).
 * BUSY — обычный ответ, не ошибка; на занятом слоте выполняется один раунд eviction мёртвого
 * держателя (истёкший TTL / мёртвый DBOS-workflow) с повторным claim'ом.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunRegistryGrpcService extends AgentRunRegistryGrpc.AgentRunRegistryImplBase {

    private final AgentRunRegistryService agentRunRegistryService;

    @Override
    public void registerRun(RegisterRunRequest request, StreamObserver<RegisterRunResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID triggerId = parseUuid(request.getTriggerId(), "trigger_id");

            RegisterResult result = registerEvictingDeadHolder(agentId, triggerId, request.getTtlSeconds());

            log.debug("AgentRunRegistry.RegisterRun pool={} run={} status={} session={}",
                    poolId, triggerId, result.status(), result.sessionId());
            responseObserver.onNext(RegisterRunResponse.newBuilder()
                    .setStatus(toProto(result.status()))
                    .setSessionKey(result.sessionId() == null ? "" : result.sessionId().toString())
                    .build());
            responseObserver.onCompleted();
        } catch (DataIntegrityViolationException e) {
            // Backstop for a true race only: two concurrent claims can both pass the statement's
            // NOT EXISTS under READ COMMITTED and the loser trips the partial unique index. The
            // regular busy-slot outcome is SlotStatus.BUSY above, not this exception.
            responseObserver.onNext(RegisterRunResponse.newBuilder()
                    .setStatus(RunSlotStatus.RUN_SLOT_STATUS_BUSY)
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentRunRegistry.RegisterRun failed pool={} run={}", poolId, request.getTriggerId(), e);
            handleError(e, responseObserver);
        }
    }

    /**
     * Register with one dead-holder eviction round: on a busy slot, evict a holder that provably
     * no longer needs it (expired lease or dead DBOS workflow — e.g. the run errored during a
     * control-api outage without releasing) and retry once. A live holder — or a lost race for
     * the freed slot — stays BUSY.
     */
    private RegisterResult registerEvictingDeadHolder(UUID agentId, UUID triggerId, int ttlSeconds) {
        RegisterResult result = agentRunRegistryService.registerRun(agentId, triggerId, ttlSeconds);
        if (result.status() != SlotStatus.BUSY) {
            return result;
        }
        if (agentRunRegistryService.reclaimDeadHolder(result.sessionId())) {
            return agentRunRegistryService.registerRun(agentId, triggerId, ttlSeconds);
        }
        return result;
    }

    private static RunSlotStatus toProto(SlotStatus status) {
        return switch (status) {
            case ACQUIRED -> RunSlotStatus.RUN_SLOT_STATUS_ACQUIRED;
            case BUSY -> RunSlotStatus.RUN_SLOT_STATUS_BUSY;
            case NO_SESSION -> RunSlotStatus.RUN_SLOT_STATUS_NO_SESSION;
        };
    }

    @Override
    public void releaseRun(ReleaseRunRequest request, StreamObserver<ReleaseRunResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID triggerId = parseUuid(request.getTriggerId(), "trigger_id");
            boolean released = agentRunRegistryService.releaseRun(agentId, triggerId);
            log.debug("AgentRunRegistry.ReleaseRun pool={} run={} released={}", poolId, triggerId, released);
            responseObserver.onNext(ReleaseRunResponse.newBuilder().setReleased(released).build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("AgentRunRegistry.ReleaseRun failed pool={} run={}", poolId, request.getTriggerId(), e);
            handleError(e, responseObserver);
        }
    }
}
