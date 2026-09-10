package ru.agimate.agentworker.workers.run;

final class Failures {

    private Failures() {
    }

    /** Links of a cause chain {@link #detail} keeps, and hops it walks — the second is the stop for a cyclic cause. */
    private static final int MAX_CAUSE_LINKS = 3;
    private static final int MAX_HOPS = 6;

    /** The exception's message, or its class name when the message is absent. */
    static String message(Throwable t) {
        String msg = t.getMessage();
        return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
    }

    /**
     * The message plus its cause chain — {@code "Request failed: SocketTimeoutException: timeout"}.
     * The wrapper usually carries the useless half: Spring AI's OpenAI client reports every network
     * failure as «Request failed» and leaves what actually happened (a read timeout, a closed
     * connection) in the cause, so a log line built from {@link #message} alone says nothing about
     * an incident.
     *
     * <p>Only for the model paths. Tool failures keep {@link #message}: that text is read by the
     * model, and our exception plumbing is not something it should be reasoning about.
     */
    static String detail(Throwable t) {
        int hops = 0;
        // A wrapper built as new X(cause) carries cause.toString() as its message and adds nothing
        // (Reactor's blockLast wraps a checked exception exactly so) — start from what it wraps.
        while (t.getCause() != null && t.getCause() != t && hops < MAX_HOPS
                && t.getCause().toString().equals(t.getMessage())) {
            t = t.getCause();
            hops++;
        }
        StringBuilder out = new StringBuilder(message(t));
        String previous = t.getMessage();
        int links = 0;
        for (Throwable cause = t.getCause();
             cause != null && links < MAX_CAUSE_LINKS && hops < MAX_HOPS;
             cause = cause.getCause(), hops++) {
            String message = cause.getMessage();
            if (message != null && message.equals(previous)) {
                // A wrapper that copied its cause's message adds a line and no information.
                continue;
            }
            out.append(": ").append(cause.getClass().getSimpleName());
            if (message != null && !message.isBlank()) {
                out.append(": ").append(message);
            }
            previous = message;
            links++;
        }
        return out.toString();
    }
}
