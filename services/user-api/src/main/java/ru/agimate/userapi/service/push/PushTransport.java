package ru.agimate.userapi.service.push;

import ru.agimate.userapi.database.entities.PushProvider;

/**
 * One transport's way of reaching one device. Implementations are picked by {@link #provider()} —
 * the column on the subscription — because credentials and wire format differ per transport and the
 * device's SDK is the one that decides which of them issued the token.
 */
public interface PushTransport {

    PushProvider provider();

    /** Whether credentials for this transport are present at all; false — the transport is skipped. */
    boolean isConfigured();

    /** Never throws: a transport failure must not reach the caller, who is delivering a message. */
    PushDelivery send(String token, PushMessage message);
}
