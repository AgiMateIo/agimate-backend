package ru.agimate.connectorsapi.service;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.device.v1.DeviceApiServiceGrpc;
import ru.agimate.device.v1.StreamTriggerEventsRequest;
import ru.agimate.device.v1.TriggerEvent;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class TriggerEventStreamListener {

    private final DeviceApiServiceGrpc.DeviceApiServiceStub deviceApiAsyncStub;
    private final WebhookLogService webhookLogService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(true);
    private StreamObserver<TriggerEvent> currentStreamObserver;

    @PostConstruct
    public void start() {
        log.info("Starting trigger event stream listener");
        running.set(true);
        shouldReconnect.set(true);
        connectToStream();
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping trigger event stream listener");
        shouldReconnect.set(false);
        running.set(false);
    }

    private void connectToStream() {
        if (!running.get()) {
            return;
        }

        log.info("Connecting to trigger event stream from device-api");

        try {
            StreamTriggerEventsRequest request = StreamTriggerEventsRequest.newBuilder().build();

            currentStreamObserver = new StreamObserver<TriggerEvent>() {
                @Override
                public void onNext(TriggerEvent event) {
                    try {
                        log.debug("Received trigger event from stream: {}", event.getEventName());
                        handleTriggerEvent(event);
                    } catch (Exception e) {
                        log.error("Error handling trigger event: {}", e.getMessage(), e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    Status status = Status.fromThrowable(t);
                    log.error("Trigger event stream error: {} - {}", status.getCode(), status.getDescription(), t);

                    if (shouldReconnect.get()) {
                        scheduleReconnect();
                    }
                }

                @Override
                public void onCompleted() {
                    log.info("Trigger event stream completed by server");

                    if (shouldReconnect.get() && running.get()) {
                        scheduleReconnect();
                    }
                }
            };

            deviceApiAsyncStub.streamTriggerEvents(request, currentStreamObserver);
            log.info("Trigger event stream established successfully");

        } catch (Exception e) {
            log.error("Failed to connect to trigger event stream: {}", e.getMessage(), e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!shouldReconnect.get() || !running.get()) {
            return;
        }

        log.info("Scheduling trigger event stream reconnection in 5 seconds...");

        Thread.startVirtualThread(() -> {
            try {
                TimeUnit.SECONDS.sleep(5);
                if (shouldReconnect.get() && running.get()) {
                    connectToStream();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Reconnection interrupted");
            }
        });
    }

    private void handleTriggerEvent(TriggerEvent event) {
        try {
            UUID userPubId = UUID.fromString(event.getUserPubId());
            String deviceId = event.getDeviceId();
            Object params = JsonUtils.readValue(event.getDataJson(), Object.class);

            webhookLogService.handleEvent(
                    event.getEventName(),
                    userPubId,
                    null,  // credentialId - not applicable for device events
                    deviceId,
                    params
            );

        } catch (Exception e) {
            log.error("Failed to process trigger event {}: {}", event.getEventName(), e.getMessage(), e);
        }
    }

    public boolean isConnected() {
        return running.get() && currentStreamObserver != null;
    }
}
