package ru.agimate.controlapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import ru.agimate.common.net.OutboundTrust;
import ru.agimate.common.net.OutboundTrustLoader;

/**
 * The trust set shared by control-api's outbound clients — the pooled Apache one and the OkHttp one
 * delivering webhooks. One bean, because a certificate that works for an MCP server and not for a
 * webhook to the same host would be nobody's intent.
 *
 * <p>Empty by default: an anchor holds for every outbound connection, not only for the host it was
 * added for, so an installation gets {@code certs/russian-trusted-root.pem} from our compose and
 * chart rather than from our jar.
 */
@Configuration
public class OutboundTrustConfig {

    @Bean
    public OutboundTrust outboundTrust(ResourceLoader resourceLoader,
                                       @Value("${app.net.trusted-ca:}") String locations) {
        return OutboundTrustLoader.load(resourceLoader, locations);
    }
}
