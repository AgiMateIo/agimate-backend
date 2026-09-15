package ru.agimate.controlapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.net.EgressProxy;

/**
 * The proxy for hosts that do not answer from where the installation runs. The worker reads the same
 * pair under {@code agent.net.proxy}; both come from one value in the chart, because a host listed
 * here and not there lists a provider's models and then fails its chat.
 */
@Configuration
public class EgressProxyConfig {

    @Bean
    public EgressProxy egressProxy(EgressProxyProperties properties) {
        return EgressProxy.of(properties.getUrl(), properties.getHosts());
    }
}
