package ru.agimate.controlapi.grpc.mapper;

import lombok.experimental.UtilityClass;
import ru.agimate.agentworker.ActiveRun;
import ru.agimate.controlapi.service.channel.AgentRunRegistryService;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.toProtoTimestamp;

/** Маппинг {@link AgentRunRegistryService.ActiveRunView} → proto {@link ActiveRun}. */
@UtilityClass
public class AgentRunRegistryMapper {

    public static ActiveRun toProto(AgentRunRegistryService.ActiveRunView view) {
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
}
