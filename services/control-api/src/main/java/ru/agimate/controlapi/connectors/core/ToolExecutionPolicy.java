package ru.agimate.controlapi.connectors.core;

import java.time.Duration;

/**
 * How a tool call may be repeated. {@link Kind#NON_REPLAYABLE} means the side effect cannot be undone
 * or safely repeated (a shell command on a remote host), so the call is dispatched at most once and
 * carries the budget after which an unfinished attempt is declared unknown.
 */
public record ToolExecutionPolicy(Kind kind, Duration executionTimeout) {

    public enum Kind {
        REPLAYABLE,
        NON_REPLAYABLE
    }

    public ToolExecutionPolicy {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        switch (kind) {
            case REPLAYABLE -> {
                if (executionTimeout != null) {
                    throw new IllegalArgumentException(
                            "REPLAYABLE tools do not carry an executionTimeout");
                }
            }
            case NON_REPLAYABLE -> {
                if (executionTimeout == null || executionTimeout.isZero() || executionTimeout.isNegative()) {
                    throw new IllegalArgumentException("executionTimeout must be positive");
                }
            }
        }
    }

    public static ToolExecutionPolicy replayable() {
        return new ToolExecutionPolicy(Kind.REPLAYABLE, null);
    }

    public static ToolExecutionPolicy nonReplayable(Duration executionTimeout) {
        return new ToolExecutionPolicy(Kind.NON_REPLAYABLE, executionTimeout);
    }
}
