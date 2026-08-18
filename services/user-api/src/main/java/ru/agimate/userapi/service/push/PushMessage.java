package ru.agimate.userapi.service.push;

import java.time.Duration;
import java.util.Map;

/**
 * A notification as the transports take it: data only, no notification block. With a notification
 * block the SDK draws the notification itself, and the application loses the ability to stay silent
 * while the person is reading that very conversation.
 *
 * <p>The content is opaque here — this service delivers it, the service that raised it knows what it
 * means (docs/decisions/push-notifications.md).
 *
 * @param data string-to-string, as every transport requires; at least one pair, or a data-only
 *             message is not a message
 * @param ttl  how long the transport keeps trying; null — the configured default
 */
public record PushMessage(Map<String, String> data, Duration ttl) {
}
