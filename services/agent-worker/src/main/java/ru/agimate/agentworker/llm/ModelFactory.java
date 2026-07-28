package ru.agimate.agentworker.llm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.JsonUtils;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds a Spring AI {@link OpenAiChatModel} from backend-provided {@link LlmCredentials}.
 *
 * <p>Only OpenAI-compatible providers are supported today (extend {@link #build} when the
 * backend returns other {@code provider_type}s). No agent/advisor is built here — the turn
 * loop is driven manually so the model call can be queued separately from tool calls.
 *
 * <p>Models are cached per {@code (baseUrl, sha256(apiKey), model)}: {@code OpenAiChatModel.build()}
 * eagerly constructs sync+async OpenAI clients (each with its own OkHttp connection pool and
 * dispatcher) and exposes no way to close them, so building per call would leak a client pair
 * per LLM request and never reuse connections. Credentials are dynamic per agent, hence a
 * bounded cache rather than a singleton; evicted idle clients self-reap (OkHttp evicts idle
 * connections after ~5 min and dispatcher threads after 60 s). A rotated api key simply becomes
 * a new cache entry. The request timeout is capped at {@link #REQUEST_TIMEOUT} (the SDK default
 * is 10 minutes, which would pin an {@code llm_calls} slot for that long on a hung provider).
 * App-identity headers are attached so the provider sees a stable {@code User-Agent} and
 * OpenRouter app attribution where applicable.
 */
@Component
@Slf4j
public class ModelFactory {

    private static final Set<String> OPENAI_COMPATIBLE =
            Set.of("openai", "openai_compatible", "openai-compatible");

    /** OpenRouter base URLs contain this host; app-attribution headers are sent only to it. */
    private static final String OPENROUTER_HOST = "openrouter.ai";

    /** Per-request LLM timeout (parity with the Python worker's httpx.Timeout(120)). */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);

    private final AgentProperties.App app;

    private final Cache<ModelKey, OpenAiChatModel> models = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /** Cache key. Carries a SHA-256 of the api key, never the secret itself (records auto-expose
     * every field via {@code toString()}, so a plaintext key would be one debug log away from
     * leaking); the plaintext for building the client travels via the {@code build} closure.
     * extraBodyJson participates too: a changed extra_body (provider routing and the like) must
     * produce a new client with new default options, not a stale cache hit. */
    private record ModelKey(String baseUrl, String apiKeyHash, String model, String extraBodyJson) {}

    public ModelFactory(AgentProperties props) {
        this.app = props.getApp();
    }

    public OpenAiChatModel build(LlmCredentials creds) {
        String providerType = creds.getProviderType().strip().toLowerCase();
        if (!OPENAI_COMPATIBLE.contains(providerType)) {
            throw new IllegalArgumentException("Unsupported provider_type: " + creds.getProviderType());
        }
        ModelKey key = new ModelKey(emptyToNull(creds.getBaseUrl()), CryptoUtils.sha256Hex(creds.getApiKey()),
                creds.getModel(), creds.getExtraBodyJson());
        return models.get(key, k -> buildModel(k.baseUrl(), creds));
    }

    private OpenAiChatModel buildModel(String baseUrl, LlmCredentials creds) {
        // Extra chat/completions body fields from the backend (the provider+model deep merge is already done there).
        // Spring AI merges them into the request itself (createRequest → extraBody). We never log them in full.
        Map<String, Object> extraBody = JsonUtils.fromJsonToMap(creds.getExtraBodyJson());
        log.info("building chat model: baseUrl={} model={} extraBodyKeys={}",
                baseUrl, creds.getModel(), extraBody.keySet());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(creds.getApiKey())
                .model(creds.getModel())
                .timeout(REQUEST_TIMEOUT)
                .customHeaders(requestHeaders(baseUrl))
                .extraBody(extraBody.isEmpty() ? null : extraBody)
                .build();
        return OpenAiChatModel.builder().options(options).build();
    }

    /**
     * App-identity headers advertised to the provider. {@code User-Agent} on every request (web
     * convention); OpenRouter app-attribution ({@code HTTP-Referer} + title/categories) only when
     * the provider is OpenRouter and an app url is configured ({@code HTTP-Referer} is its primary
     * app identifier, so without a url the title/category do nothing).
     */
    public Map<String, String> requestHeaders(String baseUrl) {
        Map<String, String> headers = new HashMap<>();
        if (app.getUserAgent() != null && !app.getUserAgent().isBlank()) {
            headers.put("User-Agent", app.getUserAgent());
        }
        if (app.getUrl() != null && !app.getUrl().isBlank()
                && baseUrl != null && baseUrl.contains(OPENROUTER_HOST)) {
            headers.put("HTTP-Referer", app.getUrl());
            if (app.getTitle() != null && !app.getTitle().isBlank()) {
                headers.put("X-OpenRouter-Title", app.getTitle());
            }
            if (app.getCategory() != null && !app.getCategory().isBlank()) {
                headers.put("X-OpenRouter-Categories", app.getCategory());
            }
        }
        return headers;
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
