package ru.agimate.connectorsapi.service;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.InternalServerErrorStatusException;
import ru.agimate.common.rest.error.ServiceUnavailableStatusException;
import ru.agimate.common.s2s.ConnectedDevice;
import ru.agimate.common.s2s.DeviceAction;
import ru.agimate.common.s2s.DeviceTrigger;
import ru.agimate.common.s2s.MobileApi;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.mobile.v1.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MobileApiService implements MobileApi {

    private final MobileApiServiceGrpc.MobileApiServiceBlockingStub mobileApiStub;

    @Override
    public List<ConnectedDevice> getDevices(String userId) {
        try {
            log.debug("Calling mobile-api gRPC service - getDevices for userId: {}", userId);

            var request = GetDevicesRequest.newBuilder()
                    .setUserId(userId)
                    .build();

            var response = mobileApiStub.getDevices(request);

            return response.getDevicesList().stream()
                    .map(device -> new ConnectedDevice(
                            device.getId(),
                            device.getName(),
                            device.getDescription()
                    ))
                    .collect(Collectors.toList());
        } catch (StatusRuntimeException e) {
            log.error("gRPC error calling getDevices: {}", e.getMessage(), e);
            throw mapGrpcException(e, "Failed to get devices from mobile-api");
        }
    }

    @Override
    public List<DeviceTrigger> getTriggers(String deviceId) {
        try {
            log.debug("Calling mobile-api gRPC service - getTriggers for deviceId: {}", deviceId);

            var request = GetTriggersRequest.newBuilder()
                    .setDeviceId(deviceId)
                    .build();

            var response = mobileApiStub.getTriggers(request);

            return response.getTriggersList().stream()
                    .map(trigger -> new DeviceTrigger(
                            trigger.getName(),
                            trigger.getDescription()
                    ))
                    .collect(Collectors.toList());
        } catch (StatusRuntimeException e) {
            log.error("gRPC error calling getTriggers: {}", e.getMessage(), e);
            throw mapGrpcException(e, "Failed to get triggers from mobile-api");
        }
    }

    @Override
    public List<DeviceAction> getActions(String deviceId) {
        try {
            log.debug("Calling mobile-api gRPC service - getActions for deviceId: {}", deviceId);

            var request = GetActionsRequest.newBuilder()
                    .setDeviceId(deviceId)
                    .build();

            var response = mobileApiStub.getActions(request);

            return response.getActionsList().stream()
                    .map(action -> new DeviceAction(
                            action.getName(),
                            action.getDescription(),
                            action.getParamsMap()
                    ))
                    .collect(Collectors.toList());
        } catch (StatusRuntimeException e) {
            log.error("gRPC error calling getActions: {}", e.getMessage(), e);
            throw mapGrpcException(e, "Failed to get actions from mobile-api");
        }
    }

    @Override
    public void pushAction(String deviceAuthKeyId, Object data) {
        try {
            log.debug("Calling mobile-api gRPC service - pushAction for deviceId: {}", deviceAuthKeyId);

            String dataJson = JsonUtils.writeValueAsString(data);

            var request = PushActionRequest.newBuilder()
                    .setDeviceAuthKeyId(deviceAuthKeyId)
                    .setDataJson(dataJson)
                    .build();

            mobileApiStub.pushAction(request);

            log.debug("Successfully pushed action to device: {}", deviceAuthKeyId);
        } catch (StatusRuntimeException e) {
            log.error("gRPC error calling pushAction: {}", e.getMessage(), e);
            throw mapGrpcException(e, "Failed to push action to mobile-api");
        }
    }

    private RuntimeException mapGrpcException(StatusRuntimeException e, String message) {
        Status.Code code = e.getStatus().getCode();

        return switch (code) {
            case UNAVAILABLE, DEADLINE_EXCEEDED ->
                new ServiceUnavailableStatusException(message + ": mobile-api service unavailable", e);
            case INVALID_ARGUMENT ->
                new InternalServerErrorStatusException(message + ": invalid request", e);
            default ->
                new InternalServerErrorStatusException(message + ": " + e.getStatus().getDescription(), e);
        };
    }
}
