package ru.agimate.mobileapi.config;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.build.BuildInfoService;

@Configuration
public class Config {

    @Bean
    public BuildInfoService buildInfoService(BuildProperties buildProperties, ApplicationContext applicationContext) {
        return new BuildInfoService(buildProperties, applicationContext);
    }
}