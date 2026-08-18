package ru.agimate.controlapi.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.config.NotificationProperties;
import ru.agimate.controlapi.database.entities.Agent;
import ru.agimate.controlapi.database.repositories.AgentRepository;
import ru.agimate.controlapi.service.webchat.WebchatAgentMessageEvent;
import ru.agimate.controlapi.service.webchat.WebchatPreviews;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebchatNotificationListener {

    /** The same name the Centrifugo event carries — one conversation, one vocabulary for the client. */
    private static final String TYPE = "webchat_message";

    private final NotificationClient notificationClient;
    private final AgentRepository agentRepository;
    private final NotificationProperties notificationProperties;

    @Async("notificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentMessage(WebchatAgentMessageEvent event) {
        try {
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
