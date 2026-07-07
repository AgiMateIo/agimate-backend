package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "dbos")
@Getter
@Setter
public class DbosProperties {

    private boolean enabled;
    private SystemDatabase systemDatabase = new SystemDatabase();

    // Queue/workflow/class/instance names are NOT config: they are the code contract with the
    // worker, shared via ru.agimate.agentworker.WorkerProtocol (libs/agentworker-proto).

    @Getter
    @Setter
    public static class SystemDatabase {
        private String url;
        private String username;
        private String password;
        private String schema = "dbos";
    }
}
