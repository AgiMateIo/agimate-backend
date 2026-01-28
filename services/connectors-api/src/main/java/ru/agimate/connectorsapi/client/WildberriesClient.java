package ru.agimate.connectorsapi.client;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.connectorsapi.connector.ConnectorMethod;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class WildberriesClient implements ConnectorClient {

    private static final String BASE_URL = "https://suppliers-api.wildberries.ru";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public String getConnectorCode() {
        return "wildberries";
    }

    @Override
    public Object execute(
            ConnectorMethod method,
            Map<String, String> credentials,
            Map<String, Object> parameters
    ) {
        String apiKey = credentials.get("apiKey");

        if (apiKey == null) {
            throw new BadRequestStatusException("Missing required credential: apiKey");
        }

        // Handle path parameters
        String endpoint = method.endpoint();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (endpoint.contains(placeholder)) {
                endpoint = endpoint.replace(placeholder, String.valueOf(entry.getValue()));
            }
        }

        String url = BASE_URL + endpoint;

        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("Authorization", apiKey)
                    .header("Content-Type", "application/json");

            if ("GET".equalsIgnoreCase(method.httpMethod())) {
                requestBuilder.get();
            } else {
                String body = JsonUtils.writeValueAsString(parameters);
                requestBuilder.method(method.httpMethod(), RequestBody.create(body, JSON));
            }

            Request request = requestBuilder.build();
            log.debug("Calling Wildberries API: {} {}", method.httpMethod(), url);

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.warn("Wildberries API error: {} - {}", response.code(), responseBody);
                    throw new BadRequestStatusException("Wildberries API error: " + response.code());
                }

                return JsonUtils.readValue(responseBody, Object.class);
            }
        } catch (IOException e) {
            log.error("Failed to call Wildberries API: {}", e.getMessage(), e);
            throw new BadRequestStatusException("Failed to call Wildberries API: " + e.getMessage());
        }
    }
}
