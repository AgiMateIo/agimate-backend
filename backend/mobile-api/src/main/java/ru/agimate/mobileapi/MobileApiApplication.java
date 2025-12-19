package ru.agimate.mobileapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableCaching
@EnableConfigurationProperties
@ConfigurationPropertiesScan(basePackages = {
        "ru.agimate.mobileapi",
        "ru.agimate.common.security.jwt"
})
@SpringBootApplication(
        scanBasePackages = {
                "ru.agimate.mobileapi",
                "ru.agimate.common"
        }
)
@EntityScan
@EnableJpaRepositories
public class MobileApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                MobileApiApplication.class,
                args
        );

    }
}
