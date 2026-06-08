package ru.agimate.controlapi.service.llm;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.util.JsonUtils;

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

    public static List<String> extractIds(ClientHttpResponse response, String arrayKey, String idKey) throws IOException {
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
                .filter(it -> it instanceof Map)
                .map(it -> (Map<?, ?>) it)
                .map(it -> it.get(idKey))
                .filter(it -> it instanceof String)
                .map(Object::toString)
                .toList();
    }

    private static String readBody(InputStream is) throws IOException {
        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 256 ? s.substring(0, 256) + "..." : s;
    }
}
