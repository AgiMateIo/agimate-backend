package ru.agimate.controlapi.config;

import org.opensolutionlab.httpclients.clients.CentrifugoClient;
import org.opensolutionlab.httpclients.configurations.CentrifugoConfigurations;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.buildinfo.BuildInfoService;

@Configuration
public class Config {

    @Bean
    public BuildInfoService buildInfoService(BuildProperties buildProperties, ApplicationContext applicationContext) {
        return new BuildInfoService(buildProperties, applicationContext);
    }

    @Bean
    public CentrifugoClient centrifugoClient(CentrifugoProperties properties) {
        CentrifugoConfigurations configurations = CentrifugoConfigurations.builder()
                .apiUrl(properties.getUrl())
                .apiPort(properties.getPort())
                .apiKey(properties.getApiKey())
                .build();
        return new CentrifugoClient(configurations);
    }
}