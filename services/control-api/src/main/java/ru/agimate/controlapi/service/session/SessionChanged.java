package ru.agimate.controlapi.service.session;

import java.util.UUID;

/**
 * Something a listing row of the session shows has changed — published by whoever changed it and
 * turned into a live event by {@link SessionEventPublisher} once the transaction commits.
 *
 * @param created the session itself is new: the client inserts the row rather than replacing one
 */
public record SessionChanged(UUID sessionId, boolean created) {

    public static SessionChanged created(UUID sessionId) {
        return new SessionChanged(sessionId, true);
    }

    public static SessionChanged updated(UUID sessionId) {
        return new SessionChanged(sessionId, false);
    }
}
