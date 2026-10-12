package ru.agimate.controlapi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SecurityConfig — CORS")
class CorsConfigurationTest {

    @Test
    @DisplayName("WWW-Authenticate открыт скрипту: браузерный MCP-клиент читает из 401 адрес метаданных")
    void exposesOAuthChallenge() {
        CorsConfiguration cors = new SecurityConfig(null, null, null, null)
                .corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("POST", "/mcp"));

        assertNotNull(cors);
        assertTrue(cors.getExposedHeaders().contains("WWW-Authenticate"));
    }
}
