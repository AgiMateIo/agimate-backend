package ru.agimate.controlapi.grpc.auth;

import io.grpc.Context;

public final class WorkerPoolContextHolder {

    public static final Context.Key<WorkerPoolContext> CONTEXT_KEY = Context.key("worker-pool");

    private WorkerPoolContextHolder() {}

    public static WorkerPoolContext current() {
        WorkerPoolContext ctx = CONTEXT_KEY.get();
        if (ctx == null) {
            throw new IllegalStateException("WorkerPoolContext is not set on current gRPC context");
        }
        return ctx;
    }
}
