package ru.agimate.agentworker.workers.run;

final class Failures {

    private Failures() {
    }

    /** The exception's message, or its class name when the message is absent. */
    static String message(Throwable t) {
        String msg = t.getMessage();
        return msg != null && !msg.isBlank() ? msg : t.getClass().getSimpleName();
    }
}
