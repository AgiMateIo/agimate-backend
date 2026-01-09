package ru.agimate.mobileapi.grpc;

import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.mobile.v1.*;
import ru.agimate.mobileapi.service.InternalMobileApiService;
import ru.agimate.mobileapi.service.TriggerEventPublisher;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MobileApiGrpcService extends MobileApiServiceGrpc.MobileApiServiceImplBase {

    private final InternalMobileApiService internalMobileApiService;
    private final TriggerEventPublisher triggerEventPublisher;

    @Override
    public void getDevices(GetDevicesRequest request, StreamObserver<GetDevicesResponse> responseObserver) {
        try {
            log.debug("gRPC getDevices called for userId: {}", request.getUserId());

            var devices = internalMobileApiService.getDevices(request.getUserId());

            var response = GetDevicesResponse.newBuilder()
                    .addAllDevices(
                            devices.stream()
                                    .map(device -> ConnectedDevice.newBuilder()
                                            .setId(device.deviceAuthKeyId())
                                            .setName(device.name())
                                            .setDescription(device.description())
                                            .build())
                                    .collect(Collectors.toList())
                    )
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in getDevices: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to get devices: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getTriggers(GetTriggersRequest request, StreamObserver<GetTriggersResponse> responseObserver) {
        try {
            log.debug("gRPC getTriggers called for deviceId: {}", request.getDeviceId());

            var triggers = internalMobileApiService.getTriggers(request.getDeviceId());

            var response = GetTriggersResponse.newBuilder()
                    .addAllTriggers(
                            triggers.stream()
                                    .map(trigger -> DeviceTrigger.newBuilder()
                                            .setName(trigger.name())
                                            .setDescription(trigger.description())
                                            .build())
                                    .collect(Collectors.toList())
                    )
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in getTriggers: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to get triggers: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void getActions(GetActionsRequest request, StreamObserver<GetActionsResponse> responseObserver) {
        try {
            log.debug("gRPC getActions called for deviceId: {}", request.getDeviceId());

            var actions = internalMobileApiService.getActions(request.getDeviceId());

            var response = GetActionsResponse.newBuilder()
                    .addAllActions(
                            actions.stream()
                                    .map(action -> DeviceAction.newBuilder()
                                            .setName(action.name())
                                            .setDescription(action.description())
                                            .putAllParams(action.params())
                                            .build())
                                    .collect(Collectors.toList())
                    )
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error in getActions: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to get actions: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void pushAction(PushActionRequest request, StreamObserver<PushActionResponse> responseObserver) {
        try {
            log.debug("gRPC pushAction called for deviceId: {} with data: {}",
                    request.getDeviceId(), request.getDataJson());

            Object data = JsonUtils.readValue(request.getDataJson(), Object.class);

            internalMobileApiService.pushAction(request.getDeviceId(), data);

            var response = PushActionResponse.newBuilder().build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            log.error("Failed to parse action data JSON: {}", e.getMessage(), e);
            responseObserver.onError(Status.INVALID_ARGUMENT
                    .withDescription("Invalid JSON data: " + e.getMessage())
                    .asRuntimeException());
        } catch (Exception e) {
            log.error("Error in pushAction: {}", e.getMessage(), e);
            responseObserver.onError(Status.INTERNAL
                    .withDescription("Failed to push action: " + e.getMessage())
                    .asRuntimeException());
        }
    }

    @Override
    public void streamTriggerEvents(
            StreamTriggerEventsRequest request,
            StreamObserver<TriggerEvent> responseObserver
    ) {
        log.info("New GRPC stream client connected for trigger events");

        ServerCallStreamObserver<TriggerEvent> serverCallObserver =
                (ServerCallStreamObserver<TriggerEvent>) responseObserver;

        TriggerEventPublisher.Subscription subscription = triggerEventPublisher.subscribe(trigger -> {
            // Check if stream is still active before sending
            if (serverCallObserver.isCancelled()) {
                log.debug("Stream is cancelled, skipping trigger event");
                return;
            }

            try {
                String eventName = "mobile." + trigger.triggerRequest().name().toLowerCase();

                TriggerEvent event = TriggerEvent.newBuilder()
                        .setEventName(eventName)
                        .setUserPubId(trigger.deviceAuthKey().getUserPubId().toString())
                        .setDeviceId(trigger.deviceAuthKey().getPubId().toString())
                        .setTriggerName(trigger.triggerRequest().name())
                        .setDataJson(trigger.triggerRequest().data() != null ? trigger.triggerRequest().data().toString() : "{}")
                        .setOccurredAt(trigger.triggerRequest().occurredAt() != null ?
                                trigger.triggerRequest().occurredAt().toString() : "")
                        .build();

                responseObserver.onNext(event);
                log.debug("Sent trigger event to GRPC stream: {}", eventName);

            } catch (Exception e) {
                log.error("Error sending trigger event to GRPC stream: {}", e.getMessage(), e);
            }
        });

        // Handle stream cancellation - unsubscribe to prevent memory leaks
        serverCallObserver.setOnCancelHandler(() -> {
            log.info("GRPC trigger event stream cancelled by client, unsubscribing");
            subscription.cancel();
        });

        log.info("GRPC trigger event stream established");
    }
}
