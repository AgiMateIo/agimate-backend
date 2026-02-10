package ru.agimate.deviceapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import ru.agimate.connectors.v1.ConnectorsEventServiceGrpc;
import ru.agimate.connectors.v1.HandleEventRequest;
import ru.agimate.deviceapi.controller.dto.request.TriggerRequest;
import ru.agimate.deviceapi.database.entities.DeviceAuthKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class TriggerNotificationService {

    private final ConnectorsEventServiceGrpc.ConnectorsEventServiceBlockingStub connectorsEventStub;

    @Async
    public void notifyTrigger(DeviceAuthKey deviceAuthKey, TriggerRequest triggerRequest) {
        try {
            String deviceId = triggerRequest.deviceId();
            if (deviceId == null && deviceAuthKey.getDevice() != null) {
                deviceId = deviceAuthKey.getDevice().getDeviceId();
            }

            HandleEventRequest request = HandleEventRequest.newBuilder()
                    .setEventName(triggerRequest.name())
                    .setUserPubId(deviceAuthKey.getUserPubId().toString())
                    .setCredentialId("")
                    .setDeviceId(deviceId != null ? deviceId : "")
                    .setParamsJson(triggerRequest.data().toString())
                    .build();

            connectorsEventStub.handleEvent(request);

            log.debug("Trigger notification sent for event: {}", triggerRequest.name());
        } catch (Exception e) {
            log.warn("Failed to notify connectors-api about trigger '{}': {}",
                    triggerRequest.name(), e.getMessage());
        }
    }
}
