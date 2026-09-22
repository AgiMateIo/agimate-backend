package ru.agimate.controlapi.service.webchat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.realtime.RealtimeEvent.WebchatMessageRecorded;
import ru.agimate.controlapi.realtime.RealtimePublisher;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.service.session.AgentSessionService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single point of delivering a webchat message to the frontend: a row in
 * {@code webchat_messages} (the UI history) plus a live event after the commit. Used both for the
 * agent's output ({@code WebchatChannelHandler.handleOutput}) and for echoing the user's messages.
 *
 * <p>The row is idempotent by {@code (session_id, message_id)}; the event is published on a replay
 * too (at-least-once) — the frontend deduplicates by {@code messageId}.
 *
 * <p>Attachments: into the row without the URL (it expires), into the event with a fresh signed link;
 * the history ({@code /manage/webchat}) issues its own links on read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebchatMessagePublisher {

    /** The stream that is work in progress, not an answer — it neither raises a badge nor previews a chat. */
    public static final String STREAM_PROGRESS = "progress";

    private final WebchatMessageRepository webchatMessageRepository;
    private final RealtimePublisher realtime;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentSessionService agentSessionService;

    @Transactional
    public void record(UUID userId, UUID agentId, UUID channelId, UUID sessionId,
                       WebchatMessageDirection direction, String stream, String messageId, String text,
                       List<Part> parts) {
        List<Map<String, Object>> storedParts = storedParts(parts);
        int inserted = webchatMessageRepository.insertIgnoreConflict(
                userId, agentId, channelId, sessionId, direction.name(), stream, messageId, text,
                storedParts == null ? null : JsonUtils.writeValueAsString(storedParts));
        if (inserted == 0) {
            log.debug("Webchat message {} already recorded in session {} (replay) - republishing event",
                    messageId, sessionId);
        }
        realtime.publish(new WebchatMessageRecorded(userId, agentId, channelId, sessionId, messageId,
                direction.name(), stream, text, storedParts, Instant.now().toString()));

        if (!STREAM_PROGRESS.equals(stream)) {
            // The preview moves whoever spoke: one's own message sent from another device included.
            agentSessionService.messageRecorded(sessionId);
        }
        if (direction == WebchatMessageDirection.AGENT && !STREAM_PROGRESS.equals(stream)) {
            // Delivery to a closed application. An event rather than a call: the push leaves after
            // the commit and off this thread, and neither is this class's business.
            eventPublisher.publishEvent(
                    new WebchatAgentMessageEvent(userId, agentId, sessionId, messageId, text));
        }
    }

    /** The stored representation of parts ({@code type/fileId/version/mime/size/name}); null — a message with no attachments. */
    private static List<Map<String, Object>> storedParts(List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return parts.stream().map(part -> {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("type", part.type());
            stored.put("fileId", part.storageRef());
            stored.put("version", part.version());
            stored.put("mime", part.mime());
            stored.put("size", part.size());
            Object name = part.meta() != null ? part.meta().get("name") : null;
            if (name != null) {
                stored.put("name", name);
            }
            return stored;
        }).toList();
    }
}
