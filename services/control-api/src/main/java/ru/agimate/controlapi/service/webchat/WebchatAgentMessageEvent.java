package ru.agimate.controlapi.service.webchat;

import java.util.UUID;

/**
 * An agent's answer has been written and published. Carries everything a subscriber needs by value:
 * listeners run after the commit, where a lazy association no longer resolves.
 *
 * <p>Only answers raise it — {@code progress} does not, the same condition that gates the badge.
 *
 * @param messageId never null: it is the NOT NULL delivery key of the row that has just been
 *                  written, so an answer without one never reaches this event
 */
public record WebchatAgentMessageEvent(
        UUID userId,
        UUID agentId,
        UUID sessionId,
        String messageId,
        String text
) {
}
