package ru.agimate.mobileapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.agimate.mobileapi.service.dto.DeviceTriggerEvent;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Service
@Slf4j
public class TriggerEventPublisher {

    private final CopyOnWriteArrayList<Consumer<DeviceTriggerEvent>> subscribers = new CopyOnWriteArrayList<>();

    public Subscription subscribe(Consumer<DeviceTriggerEvent> listener) {
        subscribers.add(listener);
        log.info("New trigger event subscriber added. Total subscribers: {}", subscribers.size());

        return () -> {
            subscribers.remove(listener);
            log.info("Trigger event subscriber removed. Total subscribers: {}", subscribers.size());
        };
    }

    public void publish(DeviceTriggerEvent trigger) {
        log.debug("Publishing trigger event: {} to {} subscriber(s)", trigger.triggerRequest().name(), subscribers.size());

        for (Consumer<DeviceTriggerEvent> subscriber : subscribers) {
            try {
                subscriber.accept(trigger);
            } catch (Exception e) {
                log.error("Error notifying subscriber of trigger event: {}", e.getMessage(), e);
            }
        }
    }

    public int getSubscriberCount() {
        return subscribers.size();
    }

    @FunctionalInterface
    public interface Subscription {
        void cancel();
    }
}
