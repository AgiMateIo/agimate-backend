package ru.agimate.userapi.service.push;

/** What one attempt to reach one device came back with. */
public enum PushDelivery {

    DELIVERED,

    /**
     * The transport does not know this token any more — the application was uninstalled or
     * reinstalled. The only reliable signal that a subscription is dead, so it is acted upon: the row
     * goes, or every later send keeps paying for it.
     */
    TOKEN_GONE,

    /** Anything else: the transport is unwell, the network is out, we are being throttled. Keep the row. */
    FAILED
}
