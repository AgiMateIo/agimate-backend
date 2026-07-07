package ru.agimate.agentworker.grpc;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import org.springframework.lang.Nullable;

/**
 * Adds the worker-pool bearer token (and optional worker-instance id) to every
 * outgoing RPC, matching control-api's {@code WorkerPoolAuthInterceptor}
 * ({@code authorization: Bearer <token>} + {@code x-worker-instance}).
 */
public class AuthClientInterceptor implements ClientInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> WORKER_INSTANCE =
            Metadata.Key.of("x-worker-instance", Metadata.ASCII_STRING_MARSHALLER);

    private final String authToken;
    @Nullable
    private final String workerInstance;

    public AuthClientInterceptor(String authToken, @Nullable String workerInstance) {
        this.authToken = authToken;
        this.workerInstance = workerInstance;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (authToken != null && !authToken.isBlank()) {
                    headers.put(AUTHORIZATION, "Bearer " + authToken);
                }
                if (workerInstance != null && !workerInstance.isBlank()) {
                    headers.put(WORKER_INSTANCE, workerInstance);
                }
                super.start(responseListener, headers);
            }
        };
    }
}
