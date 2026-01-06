package ru.agimate.connectorsapi;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "springdoc.api-docs.enabled=true",
                "springdoc.swagger-ui.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                        "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration"
        }
)
@Tag("openapi-generation")
class OpenApiGenerationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    private static final String CONTEXT_PATH = "/connectors-api";

    @Test
    void generateOpenApiSpecification() throws IOException {
        // Wait a bit for application to start
        System.out.println("Waiting for application to start on port " + port + "...");
        try {
            Thread.sleep(10000); // Wait 10 seconds for startup
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("Fetching OpenAPI spec...");

        // Fetch OpenAPI spec
        String specUrl = "http://localhost:" + port + CONTEXT_PATH + "/docs/api";
        ResponseEntity<String> response = restTemplate.getForEntity(specUrl, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode(),
                "Failed to fetch OpenAPI spec: " + response.getStatusCode());

        String spec = response.getBody();
        assertNotNull(spec, "OpenAPI spec should not be null");
        assertTrue(spec.contains("\"openapi\":\"3."),
                "Should be OpenAPI 3.x spec, got: " + spec.substring(0, Math.min(100, spec.length())));
        assertTrue(spec.contains("/api/call/ozon"),
                "Spec should contain Ozon endpoints");
        assertTrue(spec.contains("/api/call/wildberries"),
                "Spec should contain Wildberries endpoints");

        // Write to build directory
        Path outputPath = Paths.get("build/generated/openapi");
        Files.createDirectories(outputPath);
        Path outputFile = outputPath.resolve("openapi.json");

        Files.writeString(
                outputFile,
                spec,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        System.out.println("✓ Generated OpenAPI spec at: " + outputFile.toAbsolutePath());
        System.out.println("  Spec size: " + spec.length() + " bytes");
        System.out.println("  Next step: Run 'gradlew copyOpenApiSpec' to copy to resources");

        // Validate critical endpoints are present
        assertTrue(spec.contains("getProductList"), "Should contain getProductList method");
        assertTrue(spec.contains("getProductInfo"), "Should contain getProductInfo method");
        assertTrue(spec.contains("getCards"), "Should contain getCards method");
        assertTrue(spec.contains("getOrders"), "Should contain getOrders method");
    }
}
