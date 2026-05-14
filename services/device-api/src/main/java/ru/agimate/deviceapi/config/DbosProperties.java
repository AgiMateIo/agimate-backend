package ru.agimate.deviceapi.config;

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
    private Queue queue = new Queue();
    private Workflow workflow = new Workflow();

    @Getter
    @Setter
    public static class SystemDatabase {
        private String url;
        private String username;
        private String password;
        private String schema = "dbos";
    }

    @Getter
    @Setter
    public static class Queue {
        private String name;
    }

    @Getter
    @Setter
    public static class Workflow {
        private String name;
    }
}
