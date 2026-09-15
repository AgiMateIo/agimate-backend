package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The egress proxy — the same pair as the worker's {@code agent.net.proxy}. Bound rather than read with
 * {@code @Value}, which takes a comma-separated string but not a YAML list: a list there came through
 * empty and failed the start with a message about a missing half.
 */
@Component
@ConfigurationProperties(prefix = "app.net.proxy")
@Getter
@Setter
public class EgressProxyProperties {

    /** {@code http://user:password@host:port}; empty — no proxy. */
    private String url = "";

    /** Exact names or {@code *.domain}; only these go through the proxy. */
    private List<String> hosts = new ArrayList<>();
}
