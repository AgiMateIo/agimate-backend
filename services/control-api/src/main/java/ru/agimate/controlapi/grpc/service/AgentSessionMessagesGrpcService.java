package ru.agimate.controlapi.grpc.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.agentworker.AgentSessionMessagesGrpc;
import ru.agimate.agentworker.AppendMessage;
import ru.agimate.agentworker.AppendRequest;
import ru.agimate.agentworker.AppendResponse;
import ru.agimate.agentworker.GetHistoryRequest;
import ru.agimate.agentworker.GetHistoryResponse;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.database.entities.ChannelSessionMessage;
import ru.agimate.controlapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.controlapi.grpc.mapper.AgentSessionMapper;
import ru.agimate.controlapi.service.channel.AgentSessionMessagesService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static ru.agimate.controlapi.grpc.support.GrpcSupport.parseUuid;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentSessionMessagesGrpcService extends AgentSessionMessagesGrpc.AgentSessionMessagesImplBase {

    private final AgentSessionMessagesService agentSessionMessagesService;

    @Override
    public void append(AppendRequest request, StreamObserver<AppendResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentPubId = parseUuid(request.getAgentPubId(), "agent_pub_id");
            UUID sessionPubId = parseUuid(request.getSessionPubId(), "session_pub_id");
            UUID runId = parseUuid(request.getRunId(), "run_id");
            int startingTurnIdx = request.getStartingTurnIdx();
            if (startingTurnIdx < 0) {
                throw Status.INVALID_ARGUMENT
                        .withDescription("starting_turn_idx must be >= 0").asRuntimeException();
            }

            List<AgentSessionMessagesService.AppendMessage> serviceMessages = new ArrayList<>(request.getMessagesCount());
            for (AppendMessage m : request.getMessagesList()) {
                serviceMessages.add(new AgentSessionMessagesService.AppendMessage(
                        AgentSessionMapper.toDomainKind(m.getKind()),
                        m.getMessageJson().toByteArray(),
                        m.hasText() ? m.getText().getValue() : null,
                        m.getTriggerInputJson().toByteArray()
                ));
            }

            var result = agentSessionMessagesService.append(
                    agentPubId, sessionPubId, runId, startingTurnIdx, serviceMessages);

            AppendResponse.Builder builder = AppendResponse.newBuilder();
            for (Integer idx : result.assignedTurnIndices()) {
                builder.addAssignedTurnIndices(idx);
            }
            log.debug("AgentSessionMessages.Append pool={} agent={} session={} run={} count={}",
                    poolId, agentPubId, sessionPubId, runId, serviceMessages.size());
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("AgentSessionMessages.Append failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getHistory(GetHistoryRequest request, StreamObserver<GetHistoryResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentPubId = parseUuid(request.getAgentPubId(), "agent_pub_id");
            UUID sessionPubId = parseUuid(request.getSessionPubId(), "session_pub_id");

            List<ChannelSessionMessage> history = agentSessionMessagesService.getHistory(
                    agentPubId, sessionPubId, request.getLastNMessages(), request.getSinceTurn());

            GetHistoryResponse.Builder builder = GetHistoryResponse.newBuilder();
            for (ChannelSessionMessage m : history) {
                builder.addMessages(AgentSessionMapper.toHistoryMessage(m));
            }
            log.debug("AgentSessionMessages.GetHistory pool={} agent={} session={} count={}",
                    poolId, agentPubId, sessionPubId, history.size());
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("AgentSessionMessages.GetHistory failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
