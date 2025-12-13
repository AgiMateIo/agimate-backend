package ru.agimate.userapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableConfigurationProperties
@ConfigurationPropertiesScan
@SpringBootApplication(
        scanBasePackages = {
                "ru.agimate.userapi"
        }
)
@EntityScan
@EnableJpaRepositories
public class UserApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                UserApiApplication.class,
                args
        );

    }
}
