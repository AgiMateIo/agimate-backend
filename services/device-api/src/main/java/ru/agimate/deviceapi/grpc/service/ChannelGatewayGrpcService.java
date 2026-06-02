package ru.agimate.deviceapi.grpc.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ConflictStatusException;
import ru.agimate.common.rest.error.ForbiddenStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.grpc.auth.WorkerPoolContextHolder;
import ru.agimate.deviceapi.service.channel.ChannelMessageOutboundService;
import ru.agimate.deviceapi.service.channel.ChannelService;
import ru.agimate.agentworker.ChannelDescriptor;
import ru.agimate.agentworker.ChannelGatewayGrpc;
import ru.agimate.agentworker.ListChannelsRequest;
import ru.agimate.agentworker.ListChannelsResponse;
import ru.agimate.agentworker.SendChannelMessageRequest;
import ru.agimate.agentworker.SendChannelMessageResponse;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChannelGatewayGrpcService extends ChannelGatewayGrpc.ChannelGatewayImplBase {

    private final ChannelService channelService;
    private final ChannelMessageOutboundService channelMessageOutboundService;

    @Override
    public void listChannels(ListChannelsRequest request, StreamObserver<ListChannelsResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            List<Channel> channels = channelService.listForAgent(agentId);

            ListChannelsResponse.Builder responseBuilder = ListChannelsResponse.newBuilder();
            for (Channel channel : channels) {
                responseBuilder.addChannels(ChannelDescriptor.newBuilder()
                        .setChannelId(channel.getId().toString())
                        .setName(channel.getName())
                        .setReplyConnectorCode(channel.getReplyConnectorCode())
                        .setReplyToolName(channel.getReplyToolName())
                        .build());
            }
            log.debug("ChannelGateway.ListChannels pool={} agent={} count={}", poolId, agentId, channels.size());
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ChannelGateway.ListChannels failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void sendChannelMessage(SendChannelMessageRequest request, StreamObserver<SendChannelMessageResponse> responseObserver) {
        String poolId = WorkerPoolContextHolder.current().poolId();
        try {
            UUID agentId = parseUuid(request.getAgentId(), "agent_id");
            UUID channelId = parseUuid(request.getChannelId(), "channel_id");
            UUID sessionId = parseOptionalUuid(request.getSessionId(), "session_id");
            String text = request.getText();
            String toolCallId = emptyToNull(request.getToolCallId());

            var result = channelMessageOutboundService.send(agentId, channelId, sessionId, text, toolCallId);

            SendChannelMessageResponse response = SendChannelMessageResponse.newBuilder()
                    .setSessionId(result.session().getId().toString())
                    .setToolUseId(result.toolUseLog().getId().toString())
                    .build();

            log.info("ChannelGateway.SendChannelMessage pool={} agent={} channel={} session={}",
                    poolId, agentId, channelId, result.session().getId());
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (ForbiddenStatusException e) {
            responseObserver.onError(Status.PERMISSION_DENIED.withDescription(e.getMessage()).asRuntimeException());
        } catch (NotFoundStatusException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (ConflictStatusException e) {
            responseObserver.onError(Status.ABORTED.withDescription(e.getMessage()).asRuntimeException());
        } catch (io.grpc.StatusRuntimeException e) {
            responseObserver.onError(e);
        } catch (Exception e) {
            log.error("ChannelGateway.SendChannelMessage failed pool={}", poolId, e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
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

    private static UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw Status.INVALID_ARGUMENT.withDescription(field + " is not a valid UUID").asRuntimeException();
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
