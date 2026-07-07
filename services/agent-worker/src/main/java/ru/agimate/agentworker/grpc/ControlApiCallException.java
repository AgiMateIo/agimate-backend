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

    public ControlApiCallException(String rpc, Status status) {
        super(rpc + ": " + status.getCode()
                + (status.getDescription() != null ? " — " + status.getDescription() : ""));
        this.code = status.getCode();
    }

    /** gRPC status code of the failed call ({@code Status.Code} is an enum — serializable). */
    public Status.Code code() {
        return code;
    }
}
