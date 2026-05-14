package ru.agimate.deviceapi.grpc.auth;

import com.google.protobuf.Timestamp;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.deviceapi.config.WorkerPoolProperties;
import ru.agimate.deviceapi.util.AppKeyUtils;
import ru.agimate.deviceapi.util.GeneratedAppKey;
import ru.agimate.agentworker.HealthCheckRequest;
import ru.agimate.agentworker.HealthCheckResponse;
import ru.agimate.agentworker.WorkerControlGrpc;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkerPoolAuthInterceptorTest {

    private GeneratedAppKey generated;
    private Server server;
    private ManagedChannel channel;
    private String serverName;

    @BeforeEach
    void setUp() throws IOException {
        generated = AppKeyUtils.generate(WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX);
        String authkey = ParsedWorkerAuthkey.build(
                WorkerPoolRegistry.WORKER_POOL_KEY_PREFIX, generated);
        WorkerPoolProperties props = new WorkerPoolProperties(List.of(authkey));
        WorkerPoolRegistry registry = new WorkerPoolRegistry(props);
        registry.init();
        WorkerPoolKeyAuthService authService = new WorkerPoolKeyAuthService(registry);
        WorkerPoolAuthInterceptor interceptor = new WorkerPoolAuthInterceptor(authService);

        serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .intercept(interceptor)
                .addService(new TestHealthService())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();
    }

    @AfterEach
    void tearDown() {
        channel.shutdownNow();
        server.shutdownNow();
    }

    @Test
    @DisplayName("authenticated call passes through and pool context is set")
    void authenticated_passes() {
        Metadata md = bearerMetadata(generated.fullKey(), UUID.randomUUID().toString());
        WorkerControlGrpc.WorkerControlBlockingStub stub = WorkerControlGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        HealthCheckResponse response = stub.healthCheck(HealthCheckRequest.getDefaultInstance());
        assertNotNull(response);
        assertEquals("OK", response.getStatus());
        assertEquals(generated.keyId(), response.getPoolId());
    }

    @Test
    @DisplayName("missing Authorization header is rejected with UNAUTHENTICATED")
    void missingAuth_rejected() {
        WorkerControlGrpc.WorkerControlBlockingStub stub = WorkerControlGrpc.newBlockingStub(channel);
        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.healthCheck(HealthCheckRequest.getDefaultInstance()));
        assertEquals(Status.UNAUTHENTICATED.getCode(), ex.getStatus().getCode());
    }

    @Test
    @DisplayName("invalid token is rejected with UNAUTHENTICATED")
    void invalidToken_rejected() {
        Metadata md = bearerMetadata("notavalidkeyatall", UUID.randomUUID().toString());
        WorkerControlGrpc.WorkerControlBlockingStub stub = WorkerControlGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(md));

        StatusRuntimeException ex = assertThrows(StatusRuntimeException.class,
                () -> stub.healthCheck(HealthCheckRequest.getDefaultInstance()));
        assertEquals(Status.UNAUTHENTICATED.getCode(), ex.getStatus().getCode());
    }

    private static Metadata bearerMetadata(String token, String workerInstance) {
        Metadata md = new Metadata();
        md.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer " + token);
        md.put(Metadata.Key.of("x-worker-instance", Metadata.ASCII_STRING_MARSHALLER), workerInstance);
        return md;
    }

    /** Minimal in-test health service that echoes pool_id from the auth context. */
    private static class TestHealthService extends WorkerControlGrpc.WorkerControlImplBase {
        @Override
        public void healthCheck(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
            Instant now = Instant.now();
            HealthCheckResponse resp = HealthCheckResponse.newBuilder()
                    .setStatus("OK")
                    .setServerTime(Timestamp.newBuilder()
                            .setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build())
                    .setPoolId(WorkerPoolContextHolder.current().poolId())
                    .build();
            responseObserver.onNext(resp);
            responseObserver.onCompleted();
        }
    }
}
