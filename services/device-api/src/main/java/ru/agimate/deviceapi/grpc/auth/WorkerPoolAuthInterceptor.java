package ru.agimate.deviceapi.grpc.auth;

import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkerPoolAuthInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTHORIZATION = Metadata.Key.of(
            "authorization", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> WORKER_INSTANCE = Metadata.Key.of(
            "x-worker-instance", Metadata.ASCII_STRING_MARSHALLER);

    private static final String BEARER_PREFIX = "Bearer ";

    private final WorkerPoolKeyAuthService authService;

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next) {

        String token = extractBearer(headers.get(AUTHORIZATION));
        if (token == null) {
            return reject(call, Status.UNAUTHENTICATED.withDescription("Missing or malformed Authorization header"));
        }

        Optional<WorkerPoolKeyAuthService.AuthenticatedPool> auth = authService.validateKey(token);
        if (auth.isEmpty()) {
            return reject(call, Status.UNAUTHENTICATED.withDescription("Invalid worker pool key"));
        }

        String workerInstance = headers.get(WORKER_INSTANCE);
        WorkerPoolContext poolContext = new WorkerPoolContext(auth.get().poolId(), workerInstance);

        Context ctx = Context.current().withValue(WorkerPoolContextHolder.CONTEXT_KEY, poolContext);
        return Contexts.interceptCall(ctx, call, headers, next);
    }

    private static String extractBearer(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static <ReqT, RespT> ServerCall.Listener<ReqT> reject(ServerCall<ReqT, RespT> call, Status status) {
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {};
    }
}
