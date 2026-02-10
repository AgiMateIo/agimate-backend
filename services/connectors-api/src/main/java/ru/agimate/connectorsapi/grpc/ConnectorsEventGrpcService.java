package ru.agimate.connectorsapi.grpc;

import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectors.v1.ConnectorsEventServiceGrpc;
import ru.agimate.connectors.v1.HandleEventRequest;
import ru.agimate.connectors.v1.HandleEventResponse;
import ru.agimate.connectorsapi.service.WebhookLogService;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectorsEventGrpcService extends ConnectorsEventServiceGrpc.ConnectorsEventServiceImplBase {

    private final WebhookLogService webhookLogService;

    @Override
    public void handleEvent(HandleEventRequest request,
                            StreamObserver<HandleEventResponse> responseObserver) {
        try {
            log.debug("gRPC handleEvent called: event={}, user={}, device={}",
                    request.getEventName(), request.getUserPubId(), request.getDeviceId());

            UUID userPubId = UUID.fromString(request.getUserPubId());
            UUID credentialId = request.getCredentialId().isEmpty()
                    ? null
                    : UUID.fromString(request.getCredentialId());
            String deviceId = request.getDeviceId().isEmpty() ? null : request.getDeviceId();

            Map<String, Object> params = JsonUtils.fromJsonToMap(request.getParamsJson());

            webhookLogService.handleEvent(
                    request.getEventName(),
                    userPubId,
                    credentialId,
                    deviceId,
                    params
            );

            HandleEventResponse response = HandleEventResponse.newBuilder()
                    .setAccepted(true)
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in handleEvent: {}", e.getMessage(), e);
            responseObserver.onError(io.grpc.Status.INTERNAL
                    .withDescription("Failed to handle event: " + e.getMessage())
                    .asRuntimeException());
        }
    }
}
