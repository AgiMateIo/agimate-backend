package ru.agimate.controlapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "worker-pools")
public record WorkerPoolProperties(List<String> authkeys) {

    public WorkerPoolProperties {
        authkeys = authkeys == null ? List.of() : List.copyOf(authkeys);
    }
}
