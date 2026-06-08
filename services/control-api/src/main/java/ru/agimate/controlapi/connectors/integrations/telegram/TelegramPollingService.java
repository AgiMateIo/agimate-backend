package ru.agimate.controlapi.connectors.integrations.telegram;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;
import ru.agimate.controlapi.connectors.integrations.IntegrationEncryptionService;
import ru.agimate.controlapi.connectors.integrations.events.IntegrationCreatedEvent;
import ru.agimate.controlapi.connectors.integrations.events.IntegrationDeletedEvent;
import ru.agimate.controlapi.database.entities.IntegrationCredentials;
import ru.agimate.controlapi.database.repositories.IntegrationCredentialsRepository;
import ru.agimate.controlapi.service.trigger.Trigger;
import ru.agimate.controlapi.service.trigger.TriggerRouterService;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.integration.telegram.mode", havingValue = "polling")
public class TelegramPollingService {

    private static final int LONG_POLL_TIMEOUT_SEC = 20;
    private static final long BACKOFF_MS = 5_000L;
    private static final long CONFLICT_BACKOFF_MS = 30_000L;

    private final IntegrationCredentialsRepository integrationCredentialsRepository;
    private final IntegrationEncryptionService encryptionService;
    private final TelegramApiClient telegramApiClient;
    private final TelegramHandler telegramHandler;
    private final TriggerRouterService triggerRouterService;
    private final ObjectMapper objectMapper;

    private final Map<UUID, PollingWorker> workers = new ConcurrentHashMap<>();
    private final ThreadFactory threadFactory = Thread.ofVirtual().name("tg-poll-", 0).factory();

    @PostConstruct
    public void startAll() {
        List<IntegrationCredentials> active = integrationCredentialsRepository
                .findActiveByConnectorCode(TelegramHandler.CONNECTOR_CODE);
        log.info("Starting Telegram polling for {} integration(s)", active.size());
        active.forEach(this::start);
    }

    @PreDestroy
    public void stopAll() {
        log.info("Stopping Telegram polling for {} integration(s)", workers.size());
        workers.values().forEach(PollingWorker::stop);
        workers.clear();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(IntegrationCreatedEvent event) {
        if (!TelegramHandler.CONNECTOR_CODE.equals(event.connectorCode())) return;
        integrationCredentialsRepository.findByIdNotDeleted(event.integrationId())
                .filter(IntegrationCredentials::isActive)
                .ifPresent(this::start);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleted(IntegrationDeletedEvent event) {
        if (!TelegramHandler.CONNECTOR_CODE.equals(event.connectorCode())) return;
        stop(event.integrationId());
    }

    private synchronized void start(IntegrationCredentials credentials) {
        UUID id = credentials.getId();
        if (workers.containsKey(id)) {
            log.debug("Polling worker already running for {}", id);
            return;
        }
        String token;
        try {
            token = encryptionService.decryptCredentials(credentials.getEncryptedData()).get("token");
        } catch (Exception e) {
            log.error("Failed to decrypt token for integration {}: {}", id, e.getMessage());
            return;
        }
        if (token == null || token.isBlank()) {
            log.warn("Integration {} has no token, skipping", id);
            return;
        }

        PollingWorker worker = new PollingWorker(credentials, token);
        workers.put(id, worker);
        Thread thread = threadFactory.newThread(worker);
        worker.setThread(thread);
        thread.start();
        log.info("Started Telegram polling worker for integration {}", id);
    }

    private synchronized void stop(UUID id) {
        PollingWorker worker = workers.remove(id);
        if (worker != null) {
            worker.stop();
            log.info("Stopped Telegram polling worker for integration {}", id);
        }
    }

    private class PollingWorker implements Runnable {
        private final IntegrationCredentials credentials;
        private final String token;
        private volatile Long offset;
        private volatile boolean running = true;
        private volatile Thread thread;
        private boolean inConflict = false;

        PollingWorker(IntegrationCredentials credentials, String token) {
            this.credentials = credentials;
            this.token = token;
        }

        void setThread(Thread thread) {
            this.thread = thread;
        }

        void stop() {
            running = false;
            if (thread != null) thread.interrupt();
        }

        @Override
        public void run() {
            UUID id = credentials.getId();
            try {
                telegramApiClient.deleteWebhook(token);
            } catch (Exception e) {
                log.warn("Failed to delete webhook before polling for {}: {}", id, e.getMessage());
            }

            while (running) {
                try {
                    Map<String, Object> response = telegramApiClient.getUpdates(token, offset, LONG_POLL_TIMEOUT_SEC);
                    if (!Boolean.TRUE.equals(response.get("ok"))) {
                        log.warn("Telegram getUpdates failed for {}: {}", id, response.get("description"));
                        sleepBackoff(BACKOFF_MS);
                        continue;
                    }
                    if (inConflict) {
                        log.info("Telegram polling slot recovered for integration {}", id);
                        inConflict = false;
                    }
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> updates = (List<Map<String, Object>>) response.get("result");
                    if (updates == null || updates.isEmpty()) continue;

                    for (Map<String, Object> update : updates) {
                        Number updateId = (Number) update.get("update_id");
                        if (updateId != null) offset = updateId.longValue() + 1;
                        dispatch(update);
                    }
                } catch (HttpClientErrorException.Conflict e) {
                    if (!running) break;
                    if (!inConflict) {
                        log.warn("Telegram returned 409 for integration {} — another getUpdates is in flight " +
                                        "(another process with the same bot token, or a leftover session). " +
                                        "Backing off {}s until the slot is released.",
                                id, CONFLICT_BACKOFF_MS / 1000);
                        inConflict = true;
                    } else {
                        log.debug("Telegram 409 still active for {}", id);
                    }
                    sleepBackoff(CONFLICT_BACKOFF_MS);
                } catch (Exception e) {
                    if (!running) break;
                    log.error("Polling error for integration {}: {}", id, e.getMessage());
                    sleepBackoff(BACKOFF_MS);
                }
            }
            log.debug("Polling loop exited for {}", id);
        }

        private void dispatch(Map<String, Object> update) {
            try {
                String rawBody = objectMapper.writeValueAsString(update);
                Trigger trigger = telegramHandler.normalizeInbound(credentials, rawBody);
                triggerRouterService.routeWhTrigger(credentials, trigger);
            } catch (Exception e) {
                log.error("Failed to dispatch update for integration {}: {}",
                        credentials.getId(), e.getMessage());
            }
        }

        private void sleepBackoff(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }
}
