package ru.agimate.controlapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableConfigurationProperties
@ConfigurationPropertiesScan(basePackages = {
        "ru.agimate.controlapi",
        "ru.agimate.common.security.jwt"
})
@SpringBootApplication(
        scanBasePackages = {
                "ru.agimate.controlapi",
                "ru.agimate.common"
        }
)
@EntityScan
@EnableJpaRepositories
public class ControlApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                ControlApiApplication.class,
                args
        );
    }
}
