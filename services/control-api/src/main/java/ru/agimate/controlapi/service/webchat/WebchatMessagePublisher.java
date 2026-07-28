package ru.agimate.controlapi.service.webchat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;
import ru.agimate.controlapi.service.channel.handler.dto.Part;
import ru.agimate.controlapi.storage.SignedFileUrlService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The single point of delivering a webchat message to the frontend: a row in
 * {@code webchat_messages} (the UI history) plus an event in Centrifugo
 * {@code webchat:{sessionId}} (live). Used both for the agent's output
 * ({@code WebchatChannelHandler.handleOutput}) and for echoing the user's messages.
 *
 * <p>The row is idempotent by {@code (session_id, message_id)}; the event is always published
 * (at-least-once, replays included) — the frontend deduplicates by {@code messageId}, so a retry after
 * a failed publication does not lose live delivery.
 *
 * <p>Attachments: into the row without the URL (it expires), into the event with a fresh signed link;
 * the history ({@code /manage/webchat}) issues its own links on read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebchatMessagePublisher {

    public static final String CENTRIFUGO_CHANNEL_PREFIX = "webchat:";
    public static final String EVENT_TYPE = "webchat_message";

    private final WebchatMessageRepository webchatMessageRepository;
    private final CentrifugoService centrifugoService;
    private final SignedFileUrlService signedFileUrlService;

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
        centrifugoService.publishMessage(
                CENTRIFUGO_CHANNEL_PREFIX + sessionId,
                EVENT_TYPE,
                new WebchatMessageEvent(sessionId, channelId, agentId, messageId,
                        direction.name(), stream, text,
                        WebchatAttachment.fromStored(storedParts, signedFileUrlService::issue),
                        Instant.now().toString()));
    }

    /** The stored representation of parts ({@code type/fileId/mime/size}); null — a message with no attachments. */
    private static List<Map<String, Object>> storedParts(List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        return parts.stream().map(part -> {
            Map<String, Object> stored = new LinkedHashMap<>();
            stored.put("type", part.type());
            stored.put("fileId", part.storageRef());
            stored.put("mime", part.mime());
            stored.put("size", part.size());
            return stored;
        }).toList();
    }
}
