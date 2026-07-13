package ru.agimate.agentworker.grpc;

import io.grpc.Status;

/**
 * Serializable carrier of a failed control-api RPC. DBOS persists workflow/step failures via Java
 * serialization ({@code WireThrowable}), and {@link io.grpc.StatusRuntimeException} holds the
 * non-serializable {@link io.grpc.Status} — letting it escape a workflow crashes error recording
 * and masks the real failure. No cause is attached (it would drag the gRPC internals into the same
 * serialization); {@link AgentWorkerClient} logs the original exception at the call site.
 */
public class ControlApiCallException extends RuntimeException {

    private final Status.Code code;
    private final String description;

    public ControlApiCallException(String rpc, Status status) {
        super(rpc + ": " + status.getCode()
                + (status.getDescription() != null ? " — " + status.getDescription() : ""));
        this.code = status.getCode();
        this.description = status.getDescription();
    }

    /** gRPC status code of the failed call ({@code Status.Code} is an enum — serializable). */
    public Status.Code code() {
        return code;
    }

    /**
     * Raw status description without the {@code "rpc: CODE — "} prefix — the server-authored text.
     * Used to surface user-facing notices (e.g. a quota RESOURCE_EXHAUSTED message) verbatim.
     */
    public String description() {
        return description;
    }

    /**
     * Step-retry predicate ({@code StepOptions.withShouldRetry}) keeping the retry layers from
     * multiplying: UNAVAILABLE is retried by {@link AgentWorkerClient} with its own ~63s backoff
     * budget (sized to outlive a control-api restart), so a step retrying it again would wait out
     * the whole budget per attempt. Other transient codes get the step retries.
     */
    public static boolean retriableInStep(Throwable t) {
        return !(t instanceof ControlApiCallException e && e.code() == Status.Code.UNAVAILABLE);
    }
}
