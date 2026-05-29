package ru.agimate.deviceapi.grpc.service;

import com.google.protobuf.ByteString;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
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
import ru.agimate.agentworker.HistoryMessage;
import ru.agimate.agentworker.MessageKind;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;
import ru.agimate.deviceapi.database.enums.ChannelSessionMessageKind;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.deviceapi.service.channel.AgentSessionMessagesService;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
                        mapKind(m.getKind()),
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
                builder.addMessages(toHistoryMessage(m));
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

    private static HistoryMessage toHistoryMessage(ChannelSessionMessage m) {
        String json = JsonUtils.writeValueAsStringSafe(m.getMessageJson());
        HistoryMessage.Builder b = HistoryMessage.newBuilder()
                .setTurnIdx(m.getTurnIdx())
                .setKind(toProtoKind(m.getKind()))
                .setMessageJson(ByteString.copyFromUtf8(json != null ? json : "{}"))
                .setCreatedAt(toProtoTimestamp(m));
        if (m.getInputTokens() != null) {
            b.setInputTokens(Int32Value.of(m.getInputTokens()));
        }
        if (m.getOutputTokens() != null) {
            b.setOutputTokens(Int32Value.of(m.getOutputTokens()));
        }
        if (m.getModelName() != null) {
            b.setModelName(StringValue.of(m.getModelName()));
        }
        return b.build();
    }

    private static Timestamp toProtoTimestamp(ChannelSessionMessage m) {
        var instant = m.getCreatedAt().toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    private static ChannelSessionMessageKind mapKind(MessageKind kind) {
        return switch (kind) {
            case REQUEST -> ChannelSessionMessageKind.REQUEST;
            case RESPONSE -> ChannelSessionMessageKind.RESPONSE;
            default -> throw Status.INVALID_ARGUMENT
                    .withDescription("Unknown message kind: " + kind).asRuntimeException();
        };
    }

    private static MessageKind toProtoKind(ChannelSessionMessageKind kind) {
        return switch (kind) {
            case REQUEST -> MessageKind.REQUEST;
            case RESPONSE -> MessageKind.RESPONSE;
        };
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
