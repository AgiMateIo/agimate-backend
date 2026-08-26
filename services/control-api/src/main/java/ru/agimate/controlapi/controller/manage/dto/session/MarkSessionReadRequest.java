package ru.agimate.controlapi.controller.manage.dto.session;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Marking a conversation read up to a message. The id is the message's row {@code id} (the {@code id}
 * field of the history), not its {@code messageId} — the latter is the delivery key and carries no
 * order.
 */
@Schema(description = "Mark a session read")
public record MarkSessionReadRequest(
        @Schema(description = "Row id of the last message the user has seen; omit to mark the whole session read")
        UUID lastReadMessageId
) {
}
