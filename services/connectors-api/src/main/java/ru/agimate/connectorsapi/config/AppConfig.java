package ru.agimate.connectorsapi.config;

import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.buildinfo.BuildInfoService;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.common.security.jwt.JwtService;

@Configuration
public class AppConfig {

    @Bean
    public BuildInfoService buildInfoService(BuildProperties buildProperties, ApplicationContext applicationContext) {
        return new BuildInfoService(buildProperties, applicationContext);
    }

    @Bean
    public JwtService jwtService(JwtProperties jwtProperties) {
        return new JwtService(jwtProperties);
    }

}
