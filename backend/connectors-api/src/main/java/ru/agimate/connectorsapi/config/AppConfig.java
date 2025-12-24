package ru.agimate.connectorsapi.config;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import ru.agimate.common.build.BuildInfoService;

@Configuration
public class AppConfig {

    @Bean
    public BuildInfoService buildInfoService(BuildProperties buildProperties, ApplicationContext applicationContext) {
        return new BuildInfoService(buildProperties, applicationContext);
    }

}
