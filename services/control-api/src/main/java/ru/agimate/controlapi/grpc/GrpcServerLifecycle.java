package ru.agimate.controlapi.grpc;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.GrpcServerProperties;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "grpc.server", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class GrpcServerLifecycle {

    private static final Profiles DEV_PROFILES = Profiles.of("local", "test");

    private final GrpcServerProperties properties;
    private final List<BindableService> services;
    private final List<ServerInterceptor> interceptors;
    private final Environment environment;
    private Server server;

    @PostConstruct
    public void start() throws IOException {
        NettyServerBuilder builder = NettyServerBuilder.forPort(properties.port());

        for (BindableService service : services) {
            builder.addService(service);
        }
        for (ServerInterceptor interceptor : interceptors) {
            builder.intercept(interceptor);
        }

        GrpcServerProperties.Security security = properties.security();
        if (security != null && security.enabled()) {
            File cert = new File(security.certificateChain());
            File key = new File(security.privateKey());
            builder.useTransportSecurity(cert, key);
            log.info("gRPC server starting on port {} with TLS (cert={})", properties.port(), cert.getName());
        } else {
            // Users' conversations travel over the worker protocol — plaintext is acceptable in dev only.
            if (!environment.acceptsProfiles(DEV_PROFILES)) {
                throw new IllegalStateException("gRPC server without TLS is allowed only for local/test profiles; "
                        + "set grpc.server.security.* (env GRPC_SERVER_SECURITY_ENABLED etc.)");
            }
            log.warn("gRPC server starting on port {} WITHOUT TLS (development only)", properties.port());
        }

        server = builder.build().start();
        log.info("gRPC server started, services bound: {}",
                services.stream().map(s -> s.bindService().getServiceDescriptor().getName()).toList());
    }

    @PreDestroy
    public void stop() throws InterruptedException {
        if (server != null) {
            log.info("Shutting down gRPC server");
            server.shutdown().awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
