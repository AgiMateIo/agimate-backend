package ru.agimate.agentworker.config;

import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.agentworker.grpc.AuthClientInterceptor;

import javax.net.ssl.SSLException;
import java.io.File;

/**
 * gRPC channel to control-api's worker protocol. Plaintext by default; TLS with an
 * optional PEM CA cert. The worker-pool bearer token is attached to every call by
 * {@link AuthClientInterceptor}.
 */
@Configuration
@Slf4j
public class GrpcClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ManagedChannel controlApiChannel(AgentProperties props) {
        AgentProperties.Grpc grpc = props.getGrpc();
        if (!grpc.isUseTls()) {
            // По этому каналу ходит переписка пользователей — plaintext только внутри машины.
            if (!isLoopbackTarget(grpc.getTarget())) {
                throw new IllegalStateException("Plaintext gRPC to non-loopback target '" + grpc.getTarget()
                        + "' is not allowed; enable TLS (env AGENT_GRPC_USE_TLS=true)");
            }
            log.info("gRPC channel to {} (plaintext)", grpc.getTarget());
            return ManagedChannelBuilder.forTarget(grpc.getTarget()).usePlaintext().build();
        }
        if (grpc.getCaCert() == null || grpc.getCaCert().isBlank()) {
            log.info("gRPC channel to {} (TLS, system trust store)", grpc.getTarget());
            return ManagedChannelBuilder.forTarget(grpc.getTarget()).useTransportSecurity().build();
        }
        try {
            log.info("gRPC channel to {} (TLS, ca-cert={})", grpc.getTarget(), grpc.getCaCert());
            return NettyChannelBuilder.forTarget(grpc.getTarget())
                    .sslContext(GrpcSslContexts.forClient().trustManager(new File(grpc.getCaCert())).build())
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Failed to build TLS context for gRPC channel", e);
        }
    }

    /** The channel with the auth interceptor applied — inject this where stubs are built. */
    @Bean
    public Channel controlApiAuthedChannel(ManagedChannel controlApiChannel, AgentProperties props) {
        AuthClientInterceptor auth = new AuthClientInterceptor(
                props.getGrpc().getAuthToken(), props.getAgent().getId());
        return ClientInterceptors.intercept(controlApiChannel, auth);
    }

    private static boolean isLoopbackTarget(String target) {
        String host = target;
        if (host.startsWith("[")) { // [::1]:9091
            int end = host.indexOf(']');
            if (end > 0) {
                host = host.substring(1, end);
            }
        } else {
            int colon = host.indexOf(':');
            // одно двоеточие — host:port; несколько — голый IPv6-адрес
            if (colon >= 0 && colon == host.lastIndexOf(':')) {
                host = host.substring(0, colon);
            }
        }
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }
}
