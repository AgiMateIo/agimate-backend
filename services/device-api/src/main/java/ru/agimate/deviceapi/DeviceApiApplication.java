package ru.agimate.deviceapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@EnableConfigurationProperties
@ConfigurationPropertiesScan(basePackages = {
        "ru.agimate.deviceapi",
        "ru.agimate.common.security.jwt"
})
@SpringBootApplication(
        scanBasePackages = {
                "ru.agimate.deviceapi",
                "ru.agimate.common"
        }
)
@EntityScan
@EnableJpaRepositories
public class DeviceApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                DeviceApiApplication.class,
                args
        );

    }
}
