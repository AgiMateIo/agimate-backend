package ru.agimate.controlapi.connectors.integrations.ssh;

/**
 * The outcome of one remote command. A {@code null} exit code means it could not be obtained — for
 * example after a timeout — while a non-zero code is an ordinary result. A timed-out command is
 * reported as such rather than raised.
 */
public record SshCommandResult(
        String stdout,
        String stderr,
        Integer exitCode,
        boolean timedOut,
        boolean truncated,
        long durationMs) {

    /** Lengths and status only: a logged record must not repeat command output. */
    @Override
    public String toString() {
        return "SshCommandResult[stdoutChars=" + length(stdout)
                + ", stderrChars=" + length(stderr)
                + ", exitCode=" + exitCode
                + ", timedOut=" + timedOut
                + ", truncated=" + truncated
                + ", durationMs=" + durationMs + "]";
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
