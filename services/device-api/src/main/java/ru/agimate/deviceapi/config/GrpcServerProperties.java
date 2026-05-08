package ru.agimate.deviceapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "grpc.server")
public record GrpcServerProperties(
        boolean enabled,
        int port,
        @NestedConfigurationProperty Security security
) {

    public GrpcServerProperties {
        if (port == 0) {
            port = 9091;
        }
        if (security == null) {
            security = new Security(false, null, null);
        }
    }

    public record Security(boolean enabled, String certificateChain, String privateKey) {}
}
