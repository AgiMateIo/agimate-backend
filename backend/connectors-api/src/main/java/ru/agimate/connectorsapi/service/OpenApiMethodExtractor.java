package ru.agimate.connectorsapi.service;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import ru.agimate.connectorsapi.controller.dto.MethodInfo;
import ru.agimate.connectorsapi.controller.dto.ParameterInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class OpenApiMethodExtractor {

    private final ObjectProvider<OpenAPI> openAPIProvider;
    private OpenAPI cachedOpenAPI = null;

    public OpenApiMethodExtractor(ObjectProvider<OpenAPI> openAPIProvider) {
        this.openAPIProvider = openAPIProvider;
    }

    private OpenAPI getOpenAPI() {
        if (cachedOpenAPI == null) {
            cachedOpenAPI = openAPIProvider.getIfAvailable();
            if (cachedOpenAPI != null && cachedOpenAPI.getPaths() != null) {
                log.info("Loaded OpenAPI specification from springdoc runtime bean ({} paths)",
                        cachedOpenAPI.getPaths().size());
            } else {
                log.error("OpenAPI bean not available or has no paths");
            }
        }
        return cachedOpenAPI;
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
