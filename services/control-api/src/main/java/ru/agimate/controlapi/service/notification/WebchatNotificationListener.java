package ru.agimate.controlapi.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.config.NotificationProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.webchat.WebchatAgentMessageEvent;
import ru.agimate.controlapi.service.webchat.WebchatPreviews;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The agent's answer, on its way to a device whose application is closed
 * (docs/decisions/push-notifications.md). This service assembles what to say and hands it to
 * user-api, which knows which devices the person has; tokens and transports are none of its business.
 *
 * <p>Two properties of the wiring carry the whole design. <b>After the commit</b>: a notification
 * that overtakes its row makes the application open a conversation, fetch the history and not find
 * the message it was just told about. <b>Asynchronously</b>: the listener would otherwise run on the
 * thread that was delivering the answer, hanging a neighbouring service's latency onto our own
 * delivery path.
 *
 * <p>And <b>after a pause</b> ({@link NotificationProperties#getDelay()}): the phone cannot know the
 * same person is reading this conversation in a browser, but the session's read pointer does. An
 * answer read anywhere within the pause is not pushed. The pending push lives in memory — a restart
 * loses it, which a best-effort delivery can afford.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebchatNotificationListener {

    /** The same name the Centrifugo event carries — one conversation, one vocabulary for the client. */
    private static final String TYPE = "webchat_message";

    private final NotificationClient notificationClient;
    private final AgentRepository agentRepository;
    private final WebchatMessageRepository webchatMessageRepository;
    private final NotificationProperties notificationProperties;
    private final TaskScheduler taskScheduler;
    private final AsyncTaskExecutor notificationExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentMessage(WebchatAgentMessageEvent event) {
        // The scheduler's thread is shared with @Scheduled jobs: it only hands over, the call to
        // user-api runs on the notification executor.
        taskScheduler.schedule(() -> notificationExecutor.execute(() -> deliver(event)),
                Instant.now().plus(notificationProperties.getDelay()));
    }

    void deliver(WebchatAgentMessageEvent event) {
        try {
            if (webchatMessageRepository.isRead(event.sessionId(), event.messageId())) {
                log.debug("push for message {} skipped: already read", event.messageId());
                return;
            }
            notificationClient.notifyUser(event.userId(), data(event));
        } catch (Exception e) {
            // A lost notification is repaired by the next one; letting this out would fail a message
            // that is already written and published.
            log.warn("push for session {} not sent: {}", event.sessionId(), e.toString());
        }
    }

    private Map<String, String> data(WebchatAgentMessageEvent event) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", TYPE);
        data.put("sessionId", event.sessionId().toString());
        data.put("agentId", event.agentId().toString());
        data.put("agentName", agentName(event));
        data.put("messageId", event.messageId());
        if (notificationProperties.isPreview()) {
            String preview = WebchatPreviews.shorten(event.text());
            if (preview != null) {
                data.put("preview", preview);
            }
        }
        return data;
    }

    /**
     * The notification's title. An agent renamed or deleted between the answer and this call is not
     * worth failing over — the client shows what it already knows about the conversation.
     */
    private String agentName(WebchatAgentMessageEvent event) {
        return agentRepository.findById(event.agentId())
                .map(Agent::getName)
                .orElse("");
    }
}
