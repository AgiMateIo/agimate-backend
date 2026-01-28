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
public class OzonClient implements ConnectorClient {

    private static final String BASE_URL = "https://api-seller.ozon.ru";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    @Override
    public String getConnectorCode() {
        return "ozon";
    }

    @Override
    public Object execute(
            ConnectorMethod method,
            Map<String, String> credentials,
            Map<String, Object> parameters
    ) {
        String clientId = credentials.get("clientId");
        String apiKey = credentials.get("apiKey");

        if (clientId == null || apiKey == null) {
            throw new BadRequestStatusException("Missing required credentials: clientId, apiKey");
        }

        String url = BASE_URL + method.endpoint();

        try {
            Request.Builder requestBuilder = new Request.Builder()
                    .url(url)
                    .header("Client-Id", clientId)
                    .header("Api-Key", apiKey)
                    .header("Content-Type", "application/json");

            if ("GET".equalsIgnoreCase(method.httpMethod())) {
                requestBuilder.get();
            } else {
                String body = JsonUtils.writeValueAsString(parameters);
                requestBuilder.method(method.httpMethod(), RequestBody.create(body, JSON));
            }

            Request request = requestBuilder.build();
            log.debug("Calling Ozon API: {} {}", method.httpMethod(), url);

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";

                if (!response.isSuccessful()) {
                    log.warn("Ozon API error: {} - {}", response.code(), responseBody);
                    throw new BadRequestStatusException("Ozon API error: " + response.code());
                }

                return JsonUtils.readValue(responseBody, Object.class);
            }
        } catch (IOException e) {
            log.error("Failed to call Ozon API: {}", e.getMessage(), e);
            throw new BadRequestStatusException("Failed to call Ozon API: " + e.getMessage());
        }
    }
}
