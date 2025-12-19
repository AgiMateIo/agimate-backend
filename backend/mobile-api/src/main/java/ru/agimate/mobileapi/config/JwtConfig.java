package ru.agimate.mobileapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.common.security.jwt.JwtUtils;

@Configuration
public class JwtConfig {

    @Bean
    public JwtUtils jwtUtils(JwtProperties jwtProperties) {
        return new JwtUtils(jwtProperties);
    }
}
