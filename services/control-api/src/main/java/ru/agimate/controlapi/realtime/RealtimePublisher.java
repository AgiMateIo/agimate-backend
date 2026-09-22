package ru.agimate.controlapi.realtime;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.opensolutionlab.httpclients.clients.CentrifugoClient;
import org.opensolutionlab.httpclients.models.requests.publication.PublishRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.agimate.controlapi.config.CentrifugoProperties;
import ru.agimate.controlapi.realtime.RealtimeMessages.Message;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The one way into Centrifugo (docs/decisions/realtime-notifications.md). An event published inside a
 * transaction waits for its commit — a rolled-back one is never sent — and one outside a transaction
 * goes at once. A failure to send is logged and nothing else: the notification channel takes no part
 * in what the domain did, and a lost event is repaired by the client's next read.
 */
@Slf4j
@Component
public class RealtimePublisher {

    private final CentrifugoClient client;
    private final CentrifugoProperties properties;
    private final RealtimeMessages messages;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate freshReadOnly;

    public RealtimePublisher(CentrifugoClient client, CentrifugoProperties properties, RealtimeMessages messages,
                             ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.client = client;
        this.properties = properties;
        this.messages = messages;
        this.objectMapper = objectMapper;
        this.freshReadOnly = new TransactionTemplate(transactionManager);
        this.freshReadOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.freshReadOnly.setReadOnly(true);
    }

    @PostConstruct
    void warnOnIncompleteConfiguration() {
        if (properties.isEnabled() && (properties.getApiKey() == null || properties.getApiKey().isBlank())) {
            log.warn("centrifugo.api-key is not set — server-side publishing will be rejected. "
                    + "It must match http_api.key in ops/centrifugo/config.yaml");
        }
    }

    public void publish(RealtimeEvent event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(List.of(event));
            return;
        }
        batch().add(event);
    }

    /**
     * The batch is a synchronization of the current transaction rather than a resource bound by key:
     * a REQUIRES_NEW inside suspends it together with the others, so an inner transaction's events
     * never join the outer one's batch.
     */
    private Set<RealtimeEvent> batch() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof Batch batch) {
                return batch.events;
            }
        }
        Batch batch = new Batch();
        TransactionSynchronizationManager.registerSynchronization(batch);
        return batch.events;
    }

    private void send(Collection<RealtimeEvent> events) {
        for (RealtimeEvent event : events) {
            try {
                // A fresh transaction: right after the commit the finished one's context is still
                // bound, and would serve an entity loaded before a bulk UPDATE with its old values.
                List<Message> rendered = freshReadOnly.execute(status -> messages.render(event));
                rendered.forEach(this::send);
            } catch (Exception e) {
                log.warn("Realtime event {} not sent: {}", event, e.getMessage());
            }
        }
    }

    private void send(Message message) {
        if (!properties.isEnabled()) {
            log.debug("Centrifugo is disabled, not publishing to {}", message.channel());
            return;
        }
        client.publish(PublishRequest.builder()
                .channel(message.channel())
                // The client serializes with its own Jackson, which knows no java.time; converted here,
                // a payload is written exactly as the REST listing writes the same row.
                .data(objectMapper.convertValue(message.data(), Object.class))
                .tags(message.tags().isEmpty() ? null : message.tags())
                .build());
        log.debug("Published to {}", message.channel());
    }

    /** The events of one transaction; an equal record already queued is not queued twice. */
    private final class Batch implements TransactionSynchronization {

        private final Set<RealtimeEvent> events = new LinkedHashSet<>();

        @Override
        public void afterCommit() {
            send(events);
        }
    }
}
