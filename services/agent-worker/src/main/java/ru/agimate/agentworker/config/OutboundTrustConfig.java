package ru.agimate.agentworker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import ru.agimate.common.net.OutboundTrust;
import ru.agimate.common.net.OutboundTrustLoader;

/**
 * The trust set of the one address the worker does not choose — the provider's {@code base_url}.
 * Same anchors as control-api, so a provider whose models listed there cannot fail the call itself.
 * Empty by default, for the reason control-api's config gives.
 */
@Configuration
public class OutboundTrustConfig {

    @Bean
    public OutboundTrust outboundTrust(ResourceLoader resourceLoader, AgentProperties properties) {
        return OutboundTrustLoader.load(resourceLoader, properties.getNet().getTrustedCa());
    }
}
