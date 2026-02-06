package ru.agimate.deviceapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.security.jwt.JwtProperties;
import ru.agimate.common.security.jwt.JwtService;

@Configuration
public class JwtConfig {

    @Bean
    public JwtService jwtUtils(JwtProperties jwtProperties) {
        return new JwtService(jwtProperties);
    }
}
