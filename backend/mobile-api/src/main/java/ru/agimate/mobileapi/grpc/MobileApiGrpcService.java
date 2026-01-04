package ru.agimate.mobileapi.grpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.mobile.v1.*;
import ru.agimate.mobileapi.service.InternalMobileApiService;

import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MobileApiGrpcService extends MobileApiServiceGrpc.MobileApiServiceImplBase {

    private final InternalMobileApiService internalMobileApiService;

    @Override
    public void getDevices(GetDevicesRequest request, StreamObserver<GetDevicesResponse> responseObserver) {
        try {
            log.debug("gRPC getDevices called for userId: {}", request.getUserId());

            var devices = internalMobileApiService.getDevices(request.getUserId());

            var response = GetDevicesResponse.newBuilder()
                    .addAllDevices(
                            devices.stream()
                                    .map(device -> ConnectedDevice.newBuilder()
                                            .setId(device.id())
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
}
