package ru.agimate.controlapi.realtime;

import ru.agimate.controlapi.realtime.dto.ToolCallPayload;
import ru.agimate.controlapi.service.dto.AgentMessage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What happened, as the domain tells {@link RealtimePublisher}. Plain facts: which channel, type,
 * tags and payload each one becomes is {@link RealtimeMessages}' business, and it is decided only
 * after the commit. Equal records within one transaction are sent once.
 */
public sealed interface RealtimeEvent {

    /** A listing row of the session moved; {@code created} — the session itself is new. */
    record SessionChanged(UUID sessionId, boolean created) implements RealtimeEvent {

        public static SessionChanged created(UUID sessionId) {
            return new SessionChanged(sessionId, true);
        }

        public static SessionChanged updated(UUID sessionId) {
            return new SessionChanged(sessionId, false);
        }
    }

    /**
     * A message landed in the webchat UI log.
     *
     * @param parts the stored attachments; the links to them are signed when the event is sent
     */
    record WebchatMessageRecorded(UUID userId, UUID agentId, UUID channelId, UUID sessionId,
                                  String messageId, String direction, String stream, String text,
                                  List<Map<String, Object>> parts, String createdAt) implements RealtimeEvent {
    }

    /** A board task changed; {@code type} is one of {@code BoardEventType}. */
    record BoardTaskChanged(UUID userId, UUID boardId, String type, Object task) implements RealtimeEvent {
    }

    /** A request between the agents of a team changed. */
    record AgentRequestChanged(UUID threadId, String type) implements RealtimeEvent {

        public static final String STARTED = "agent.request.started";
        public static final String APPENDED = "agent.request.appended";
        public static final String REPORTED = "agent.request.reported";
        public static final String CANCELLED = "agent.request.cancelled";
    }

    /** A trigger or a tool result for an external agent. */
    record AgentDelivery(UUID agentId, AgentMessage<?> message) implements RealtimeEvent {
    }

    /** A tool call for a connected application. */
    record AppToolCall(UUID appId, ToolCallPayload payload) implements RealtimeEvent {
    }
}
