package ru.agimate.controlapi.realtime.dto;

import ru.agimate.controlapi.service.webchat.WebchatAttachment;

import java.util.List;
import java.util.UUID;

/**
 * Payload of a webchat message: {@code webchat.message} in {@code user:{userId}}, and
 * {@code webchat_message} in {@code webchat:{sessionId}} until the clients move over. Delivered
 * at-least-once — the frontend deduplicates by {@code messageId}.
 *
 * @param direction {@code USER} (an echo of the user's message) or {@code AGENT}
 * @param stream    the agent's output stream: {@code answer}/{@code progress}/{@code error}; null for USER
 * @param parts     attachments with fresh signed links; null — a message with no attachments. A link
 *                  lives {@code app.files.url-ttl} — once it expires the frontend re-reads the history
 *                  and gets a new one
 */
public record WebchatMessagePayload(
        UUID sessionId,
        UUID channelId,
        UUID agentId,
        String messageId,
        String direction,
        String stream,
        String text,
        List<WebchatAttachment> parts,
        String createdAt
) {
}
