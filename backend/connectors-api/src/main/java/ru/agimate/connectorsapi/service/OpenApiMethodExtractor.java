package ru.agimate.connectorsapi.service;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import ru.agimate.connectorsapi.controller.dto.MethodInfo;
import ru.agimate.connectorsapi.controller.dto.ParameterInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OpenApiMethodExtractor {

    private static final String DEFAULT_GROUP = "connectors";

    private OpenAPI openAPI;

    @Value("${server.port:8280}")
    private int serverPort;

    @Value("${server.servlet.context-path:/connectors-api}")
    private String contextPath;

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String apiDocsPath;

    @EventListener(ApplicationReadyEvent.class)
    public void loadOpenApiSpec() {
        // Try dynamic loading via SpringDoc REST endpoint first
        if (tryLoadViaSpringDocEndpoint()) {
            return;
        }

        // Fallback to static file
        log.info("Falling back to static OpenAPI specification file");
        loadFromStaticFile();
    }

    /**
     * Attempts to load OpenAPI specification dynamically via SpringDoc REST endpoint.
     * This correctly handles GroupedOpenApi configurations by fetching the specific group.
     *
     * @return true if successful, false otherwise
     */
    private boolean tryLoadViaSpringDocEndpoint() {
        try {
            // Build URL, ensuring no double slashes
            String basePath = contextPath.endsWith("/") ? contextPath.substring(0, contextPath.length() - 1) : contextPath;
            String docsPath = apiDocsPath.startsWith("/") ? apiDocsPath : "/" + apiDocsPath;
            String url = "http://localhost:" + serverPort + basePath + docsPath + "/" + DEFAULT_GROUP;
            log.debug("Fetching OpenAPI specification from: {}", url);

            RestClient restClient = RestClient.create();
            String jsonContent = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(String.class);

            if (jsonContent == null || jsonContent.isBlank()) {
                log.warn("SpringDoc endpoint returned empty response");
                return false;
            }

            // Parse JSON using swagger-parser
            OpenAPIV3Parser parser = new OpenAPIV3Parser();
            SwaggerParseResult result = parser.readContents(jsonContent, null, null);

            if (result.getMessages() != null && !result.getMessages().isEmpty()) {
                log.warn("OpenAPI parsing warnings: {}", result.getMessages());
            }

            openAPI = result.getOpenAPI();

            if (openAPI != null && openAPI.getPaths() != null && !openAPI.getPaths().isEmpty()) {
                log.info("Successfully loaded OpenAPI specification via SpringDoc endpoint ({} paths)",
                        openAPI.getPaths().size());
                return true;
            } else {
                log.warn("SpringDoc endpoint returned OpenAPI with no paths");
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to load OpenAPI specification via SpringDoc endpoint: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Loads OpenAPI specification from static file (fallback method).
     */
    private void loadFromStaticFile() {
        try {
            ClassPathResource resource = new ClassPathResource("static/openapi.json");
            if (!resource.exists()) {
                log.warn("OpenAPI specification file not found at classpath:static/openapi.json");
                return;
            }

            // Read file content as string
            String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

            // Parse using swagger-parser
            OpenAPIV3Parser parser = new OpenAPIV3Parser();
            SwaggerParseResult result = parser.readContents(content, null, null);

            if (result.getMessages() != null && !result.getMessages().isEmpty()) {
                log.warn("OpenAPI parsing warnings: {}", result.getMessages());
            }

            openAPI = result.getOpenAPI();

            if (openAPI != null && openAPI.getPaths() != null) {
                log.info("Successfully loaded OpenAPI specification from static file ({} paths)",
                        openAPI.getPaths().size());
            } else {
                log.error("Failed to parse OpenAPI specification - result is null or has no paths");
            }
        } catch (IOException e) {
            log.error("Failed to load OpenAPI specification from static file", e);
        }
    }

    /**
     * Извлекает все методы для указанного коннектора из OpenAPI спецификации
     *
     * @param connectorCode код коннектора (например, "ozon", "wildberries")
     * @return список методов коннектора
     */
    public List<MethodInfo> extractMethodsForConnector(String connectorCode) {
        if (openAPI == null || openAPI.getPaths() == null) {
            log.warn("OpenAPI specification is not available");
            return List.of();
        }

        List<MethodInfo> methods = new ArrayList<>();
        String pathPrefix = "/api/call/" + connectorCode.toLowerCase() + "/";

        // Iterate through all paths in OpenAPI spec
        for (Map.Entry<String, PathItem> entry : openAPI.getPaths().entrySet()) {
            String path = entry.getKey();
            PathItem pathItem = entry.getValue();

            // Check if path matches our connector
            if (!path.startsWith(pathPrefix)) {
                continue;
            }

            // Extract method name from path (last segment)
            String methodName = extractMethodName(path, pathPrefix);

            // Process each HTTP method (GET, POST, PUT, DELETE, etc.)
            processOperation(pathItem.getGet(), "GET", path, methodName, methods);
            processOperation(pathItem.getPost(), "POST", path, methodName, methods);
            processOperation(pathItem.getPut(), "PUT", path, methodName, methods);
            processOperation(pathItem.getDelete(), "DELETE", path, methodName, methods);
            processOperation(pathItem.getPatch(), "PATCH", path, methodName, methods);
        }

        log.info("Extracted {} methods for connector '{}'", methods.size(), connectorCode);
        return methods;
    }

    private void processOperation(
            Operation operation,
            String httpMethod,
            String path,
            String methodName,
            List<MethodInfo> methods
    ) {
        if (operation == null) {
            return;
        }

        String displayName = operation.getSummary() != null ? operation.getSummary() : methodName;
        String description = operation.getDescription() != null ? operation.getDescription() : "";
        List<ParameterInfo> parameters = extractParameters(operation);

        methods.add(new MethodInfo(
                methodName,
                displayName,
                description,
                httpMethod,
                path,
                parameters
        ));
    }

    private List<ParameterInfo> extractParameters(Operation operation) {
        List<ParameterInfo> parameters = new ArrayList<>();

        // Extract query and path parameters
        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                String name = parameter.getName();
                String type = parameter.getSchema() != null ? parameter.getSchema().getType() : "string";
                boolean required = Boolean.TRUE.equals(parameter.getRequired());
                String description = parameter.getDescription() != null ? parameter.getDescription() : "";

                parameters.add(new ParameterInfo(name, type, required, description));
            }
        }

        // Extract request body parameters (for POST/PUT/PATCH)
        if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
            var content = operation.getRequestBody().getContent().get("application/json");
            if (content != null && content.getSchema() != null) {
                Schema<?> schema = content.getSchema();
                extractSchemaProperties(schema, parameters, operation.getRequestBody().getRequired());
            }
        }

        return parameters;
    }

    @SuppressWarnings("unchecked")
    private void extractSchemaProperties(Schema<?> schema, List<ParameterInfo> parameters, Boolean isRequired) {
        if (schema.getProperties() != null) {
            Map<String, Schema> properties = schema.getProperties();
            List<String> requiredFields = schema.getRequired() != null ? schema.getRequired() : List.of();

            for (Map.Entry<String, Schema> entry : properties.entrySet()) {
                String name = entry.getKey();
                Schema<?> propertySchema = entry.getValue();
                String type = propertySchema.getType() != null ? propertySchema.getType() : "object";
                boolean required = requiredFields.contains(name);
                String description = propertySchema.getDescription() != null ? propertySchema.getDescription() : "";

                parameters.add(new ParameterInfo(name, type, required, description));
            }
        }
    }

    private String extractMethodName(String path, String prefix) {
        String withoutPrefix = path.substring(prefix.length());
        // Remove path variables like {id}
        int braceIndex = withoutPrefix.indexOf('{');
        if (braceIndex > 0) {
            withoutPrefix = withoutPrefix.substring(0, braceIndex);
        }
        // Remove trailing slashes
        return withoutPrefix.replaceAll("/$", "");
    }
}
