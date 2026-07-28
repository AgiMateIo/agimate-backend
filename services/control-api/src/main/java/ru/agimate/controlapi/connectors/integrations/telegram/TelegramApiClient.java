package ru.agimate.controlapi.connectors.integrations.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.connectors.core.AttributionHeaders;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class TelegramApiClient {

    private static final String BASE_URL = "https://api.telegram.org";
    private static final Duration LONG_POLL_READ_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration SEND_READ_TIMEOUT = Duration.ofSeconds(120);

    private final RestClient restClient;
    private final RestClient longPollClient;

    public TelegramApiClient(AttributionHeaders attribution) {
        String userAgent = attribution.userAgent();
        // An explicit streaming factory (the JDK HttpClient): a multipart body (up to 50 MB) is not buffered in
        // the heap. The default builder picks a factory by classpath detection (right now HttpComponents arrives
        // transitively with the AWS SDK) — that cannot be relied upon.
        JdkClientHttpRequestFactory sendFactory = new JdkClientHttpRequestFactory();
        sendFactory.setReadTimeout(SEND_READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(sendFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();

        SimpleClientHttpRequestFactory longPollFactory = new SimpleClientHttpRequestFactory();
        longPollFactory.setReadTimeout(LONG_POLL_READ_TIMEOUT);
        this.longPollClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(longPollFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .build();
    }

    public Map<String, Object> getMe(String token) {
        String body = restClient.get()
                .uri("/bot{token}/getMe", token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJsonToMap(body);
    }

    public Map<String, Object> setWebhook(String token, String url, String secretToken) {
        String body = restClient.post()
                .uri("/bot{token}/setWebhook", token)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "url", url,
                        "secret_token", secretToken
                ))
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJsonToMap(body);
    }

    public Map<String, Object> deleteWebhook(String token) {
        String body = restClient.post()
                .uri("/bot{token}/deleteWebhook", token)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJsonToMap(body);
    }

    public Map<String, Object> sendRequest(String method, String token, Map<String, Object> params) {
        String body = restClient.post()
                .uri("/bot{token}/{method}", token, method)
                .accept(MediaType.APPLICATION_JSON)
                .body(params)
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJsonToMap(body);
    }

    /**
     * Calls a Bot API method uploading binary content as multipart (sendPhoto/sendDocument/sendVideo
     * with bytes instead of a URL or a file_id). The remaining parameters go as text parts.
     * Telegram's bot upload limit is 50 MB.
     */
    public Map<String, Object> sendRequestMultipart(String method, String token, Map<String, Object> params,
                                                    String fileField, String filename, String mime,
                                                    InputStream content, long contentLength) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        params.forEach((key, value) -> {
            if (value != null) {
                builder.part(key, value.toString());
            }
        });
        InputStreamResource resource = new InputStreamResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }

            @Override
            public long contentLength() {
                // The base reads the stream just to get the length — but the size is known from the file's metadata.
                return contentLength;
            }
        };
        builder.part(fileField, resource, safeMediaType(mime));

        String body = restClient.post()
                .uri("/bot{token}/{method}", token, method)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(builder.build())
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJsonToMap(body);
    }

    private static MediaType safeMediaType(String mime) {
        try {
            return MediaType.parseMediaType(mime);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    /** Metadata of a bot's file ({@code file_path}, {@code file_size}) by its {@code file_id}. */
    public Map<String, Object> getFile(String token, String fileId) {
        return sendRequest("getFile", token, Map.of("file_id", fileId));
    }

    /**
     * Downloads a bot's file contents by the {@code file_path} from {@link #getFile} (buffered in
     * memory — a bot's download limit is ~20 MB). The host is the same but the path prefix differs
     * ({@code /file/bot…}). The URI is assembled as a string: {@code file_path} contains slashes,
     * which a RestClient template would encode.
     */
    public byte[] downloadFile(String token, String filePath) {
        return restClient.get()
                .uri(java.net.URI.create(BASE_URL + "/file/bot" + token + "/" + filePath))
                .retrieve()
                .body(byte[].class);
    }

    public Map<String, Object> getUpdates(String token, Long offset, int timeoutSec) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (offset != null) body.put("offset", offset);
        body.put("timeout", timeoutSec);
        String response = longPollClient.post()
                .uri("/bot{token}/getUpdates", token)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return JsonUtils.fromJsonToMap(response);
    }
}
