package ru.agimate.agentworker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.net.EgressProxy;

/** The proxy for providers that do not answer from where the worker runs — see control-api's config. */
@Configuration
public class EgressProxyConfig {

    @Bean
    public EgressProxy egressProxy(AgentProperties properties) {
        AgentProperties.ProxySettings proxy = properties.getNet().getProxy();
        return EgressProxy.of(proxy.getUrl(), proxy.getHosts());
    }
}
