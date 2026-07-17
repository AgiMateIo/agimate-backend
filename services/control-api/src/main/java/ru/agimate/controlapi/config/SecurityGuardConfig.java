package ru.agimate.controlapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import ru.agimate.common.security.SecurityPropertiesGuard;

import java.util.List;

@Configuration
public class SecurityGuardConfig {

    @Bean
    public SecurityPropertiesGuard securityPropertiesGuard(Environment environment) {
        return new SecurityPropertiesGuard(environment, List.of(
                "app.secrets.encryption-key",
                "app.files.url-secret",
                "jwt.publicKey"));
    }
}
