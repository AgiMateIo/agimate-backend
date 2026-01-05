package ru.agimate.connectorsapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Value("${server.servlet.context-path:/connectors-api}")
    private String contextPath;

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Agimate Connectors API")
                        .version("1.0.0")
                        .description("API for integrating with marketplace connectors like Ozon and Wildberries. " +
                                "Provides connector execution endpoints with full type safety and OpenAPI documentation.")
                        .contact(new Contact()
                                .name("Agimate Support")
                                .email("support@agimate.ru")))
                .addSecurityItem(new SecurityRequirement().addList("ApiKey"))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("ApiKey",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)
                                        .name("X-API-Key")
                                        .description("API Key for connector execution. Obtain from /api-keys endpoint."))
                        .addSecuritySchemes("BearerAuth",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("JWT token for management operations")))
                .servers(List.of(
                        new Server().url("http://localhost:8280" + contextPath).description("Local development"),
                        new Server().url("https://api.agimate.ru" + contextPath).description("Production")
                ));
    }

    @Bean
    public GroupedOpenApi connectorApiGroup() {
        return GroupedOpenApi.builder()
                .group("connectors")
                .displayName("Connector Execution API")
                .pathsToMatch("/api/call/**", "/api/methods/**")
                .build();
    }

    @Bean
    public GroupedOpenApi mobileApiGroup() {
        return GroupedOpenApi.builder()
                .group("mobile")
                .displayName("Mobile Device API")
                .pathsToMatch("/api/call/mobile/**")
                .build();
    }

    @Bean
    public GroupedOpenApi managementApiGroup() {
        return GroupedOpenApi.builder()
                .group("management")
                .displayName("Management API")
                .pathsToMatch("/connectors/**", "/credentials/**", "/api-keys/**")
                .build();
    }
}
