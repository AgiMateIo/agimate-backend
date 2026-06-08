package ru.agimate.controlapi.config;

import dev.dbos.transact.DBOSClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class DbosConfig {

    private final DbosProperties props;

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "dbos", name = "enabled", havingValue = "true")
    public DBOSClient dbosClient() {
        DbosProperties.SystemDatabase db = props.getSystemDatabase();
        return new DBOSClient(db.getUrl(), db.getUsername(), db.getPassword(), db.getSchema());
    }
}
