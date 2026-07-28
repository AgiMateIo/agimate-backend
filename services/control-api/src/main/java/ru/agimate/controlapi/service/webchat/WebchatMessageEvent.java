package ru.agimate.controlapi.service.webchat;

import java.util.List;
import java.util.UUID;

/**
 * Payload of the {@code webchat_message} event in the Centrifugo channel {@code webchat:{sessionId}}.
 * Events are delivered at-least-once — the frontend deduplicates by {@code messageId}.
 *
 * @param direction {@code USER} (an echo of the user's message) or {@code AGENT}
 * @param stream    the agent's output stream: {@code answer}/{@code progress}/{@code error}; null for USER
 * @param parts     attachments with fresh signed links; null — a message with no attachments. A link
 *                  lives {@code app.files.url-ttl} — once it expires the frontend re-reads the history
 *                  and gets a new one
 */
public record WebchatMessageEvent(
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
