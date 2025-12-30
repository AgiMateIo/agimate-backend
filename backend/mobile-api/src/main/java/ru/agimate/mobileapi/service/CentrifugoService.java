package ru.agimate.mobileapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensolutionlab.httpclients.clients.CentrifugoClient;
import org.springframework.stereotype.Service;
import ru.agimate.common.rest.error.ServiceUnavailableStatusException;
import ru.agimate.mobileapi.config.CentrifugoProperties;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CentrifugoService {

    private final CentrifugoClient centrifugoClient;
    private final CentrifugoProperties centrifugoProperties;

    /**
     * Publishes a message to a Centrifugo channel.
     *
     * @param channel The channel name
     * @param data The message data (will be serialized to JSON)
     * @throws ServiceUnavailableStatusException if Centrifugo is unavailable or publishing fails
     */
    public void publishMessage(String channel, Object data) {
        if (!centrifugoProperties.isEnabled()) {
            log.warn("Centrifugo is disabled, skipping publish to channel: {}", channel);
            return;
        }

        try {
            log.debug("Publishing message to Centrifugo channel: {}", channel);

            centrifugoClient.publish(channel, data);

            log.info("Successfully published message to channel: {}", channel);
        } catch (Exception e) {
            log.error("Failed to publish message to Centrifugo channel '{}': {}",
                    channel, e.getMessage(), e);
            throw new ServiceUnavailableStatusException(
                    "Failed to publish message to real-time service: " + e.getMessage(), e);
        }
    }

    /**
     * Creates a test message with current timestamp.
     *
     * @return A map containing type and timestamp
     */
    public Map<String, Object> createTestMessage() {
        Map<String, Object> message = new HashMap<>();
        message.put("type", "test");
        message.put("timestamp", System.currentTimeMillis());
        return message;
    }
}
