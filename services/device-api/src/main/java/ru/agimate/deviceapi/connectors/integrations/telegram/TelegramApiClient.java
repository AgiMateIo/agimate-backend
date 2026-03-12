package ru.agimate.deviceapi.connectors.integrations.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
public class TelegramApiClient {

    private static final String BASE_URL = "https://api.telegram.org";

    private final RestClient restClient;

    public TelegramApiClient() {
        this.restClient = RestClient.builder().baseUrl(BASE_URL).build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMe(String token) {
        return restClient.get()
                .uri("/bot{token}/getMe", token)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> setWebhook(String token, String url, String secretToken) {
        return restClient.post()
                .uri("/bot{token}/setWebhook", token)
                .body(Map.of(
                        "url", url,
                        "secret_token", secretToken
                ))
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> deleteWebhook(String token) {
        return restClient.post()
                .uri("/bot{token}/deleteWebhook", token)
                .body(Map.of())
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> sendRequest(String method, String token, Map<String, Object> params) {
        return restClient.post()
                .uri("/bot{token}/{method}", token, method)
                .body(params)
                .retrieve()
                .body(Map.class);
    }
}
