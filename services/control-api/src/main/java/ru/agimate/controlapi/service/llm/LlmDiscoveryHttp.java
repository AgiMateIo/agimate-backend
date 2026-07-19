package ru.agimate.controlapi.service.llm;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.model.LlmModelInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@UtilityClass
public class LlmDiscoveryHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    public static RestClient client(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) CONNECT_TIMEOUT.toMillis());
        factory.setReadTimeout((int) READ_TIMEOUT.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Extracts model entries from a provider's "list models" response.
     *
     * @param arrayKey       the JSON key under which the array of model objects lives (e.g. {@code "data"} or {@code "models"}).
     * @param idKey          field on each entry holding the model id.
     * @param displayNameKey optional field on each entry holding the human-readable name; pass {@code null} if the provider
     *                       doesn't expose one — {@link LlmModelInfo#displayName()} will be {@code null}.
     */
    public static List<LlmModelInfo> extractModels(
            ClientHttpResponse response,
            String arrayKey,
            String idKey,
            String displayNameKey
    ) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) {
            String body = readBody(response.getBody());
            log.warn("LLM models endpoint returned {}: {}", response.getStatusCode(), body);
            throw new BadRequestStatusException(
                    "LLM provider rejected request (" + response.getStatusCode().value() + "): " + truncate(body));
        }
        String body = readBody(response.getBody());
        Map<String, Object> root = JsonUtils.fromJsonToMap(body);
        Object arr = root.get(arrayKey);
        if (!(arr instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(entry -> toModelInfo(entry, idKey, displayNameKey))
                .filter(it -> it != null)
                .toList();
    }

    private static LlmModelInfo toModelInfo(Map<?, ?> entry, String idKey, String displayNameKey) {
        Object id = entry.get(idKey);
        if (!(id instanceof String idStr) || idStr.isBlank()) {
            return null;
        }
        String displayName = null;
        if (displayNameKey != null) {
            Object raw = entry.get(displayNameKey);
            if (raw instanceof String s && !s.isBlank()) {
                displayName = s;
            }
        }
        return new LlmModelInfo(idStr, displayName,
                intOrNull(entry.get("context_length")),
                stringListOrNull(entry.get("architecture") instanceof Map<?, ?> arch
                        ? arch.get("input_modalities") : null),
                stringListOrNull(entry.get("supported_parameters")));
    }

    /** Опортунистические метаданные OpenRouter-стиля; у не отдающих их провайдеров остаются null. */
    private static Integer intOrNull(Object raw) {
        return raw instanceof Number n ? n.intValue() : null;
    }

    private static List<String> stringListOrNull(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> result = list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
        return result.isEmpty() ? null : result;
    }

    private static String readBody(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }
}
