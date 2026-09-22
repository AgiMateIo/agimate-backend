package ru.agimate.controlapi.realtime.dto;

import java.util.UUID;

/**
 * Payload of the {@code webchat_activity} event in the user's own Centrifugo channel
 * {@code user:{userId}} — the badge of a client that is looking at the contact list and is
 * subscribed to no conversation in particular.
 *
 * <p>Deliberately thin: it carries where the message landed and a preview, not the message itself.
 * The message travels its own channel, and a client that has the conversation open renders it from
 * there; this one only has to make a number grow.
 *
 * @param preview the message text, truncated — a badge never needs the whole answer
 * @deprecated superseded by {@code webchat.agent.updated}; goes once the web and Android clients
 *             have moved over (docs/decisions/realtime-notifications.md)
 */
@Deprecated
public record WebchatActivityPayload(
        UUID agentId,
        UUID sessionId,
        String messageId,
        String stream,
        String preview,
        String createdAt
) {
}
