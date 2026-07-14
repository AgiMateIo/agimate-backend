package ru.agimate.userapi.config;

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
                "jwt.privateKey",
                "jwt.publicKey",
                "app.oauth.cookie-encryption-key"));
    }
}
