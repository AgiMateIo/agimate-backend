package ru.agimate.agentworker.llm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.common.net.PublicOnlySslSocketFactory;
import ru.agimate.common.net.PublicTargets;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.JsonUtils;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a Spring AI {@link OpenAiChatModel} from backend-provided {@link LlmCredentials}.
 *
 * <p>Only OpenAI-compatible providers are supported today (extend {@link #build} when the
 * backend returns other {@code provider_type}s). No agent/advisor is built here — the turn
 * loop is driven manually so the model call can be queued separately from tool calls.
 *
 * <p>Per-call request options come from here too ({@link #requestOptions}): Spring AI 2.0 does not
 * merge a prompt's options with the model's defaults ({@code buildRequestPrompt} keeps the prompt's
 * as-is), so everything that must reach the request body — the model and {@code extra_body} —
 * belongs to the per-call options, and building them anywhere else silently drops it.
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

    /**
     * The base url belongs to whoever created the provider, so the call is a request forgery target:
     * it leaves our network with an api key on it, and its answer lands in the agent's history where
     * whoever wrote the prompt can read it. Both halves of the guard are needed — the url is vetted
     * per call below, and {@link PublicOnlySslSocketFactory} checks the connected socket, which is
     * what survives a name that answers differently the second time it is asked.
     */
    private final PublicTargets targets;

    private final Cache<ModelKey, OpenAiChatModel> models = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterAccess(Duration.ofMinutes(30))
            .build();

    /** Cache key. Carries a SHA-256 of the api key, never the secret itself (records auto-expose
     * every field via {@code toString()}, so a plaintext key would be one debug log away from
     * leaking); the plaintext for building the client travels via the {@code build} closure.
     * extra_body is deliberately absent: it rides on the per-call options, so the same client
     * serves any of them. */
    private record ModelKey(String baseUrl, String apiKeyHash, String model) {}

    public ModelFactory(AgentProperties props) {
        this.app = props.getApp();
        this.targets = new PublicTargets(props.getNet().isAllowPrivateTargets());
    }

    public OpenAiChatModel build(LlmCredentials creds) {
        String providerType = creds.getProviderType().strip().toLowerCase();
        if (!OPENAI_COMPATIBLE.contains(providerType)) {
            throw new IllegalArgumentException("Unsupported provider_type: " + creds.getProviderType());
        }
        String baseUrl = emptyToNull(creds.getBaseUrl());
        if (baseUrl != null) {
            // Per call, not per cached client: the cache would otherwise vet an address once and
            // keep using it for half an hour. https is required alongside, because the socket-level
            // check below happens during the TLS handshake and a plain-http call never reaches it.
            targets.requireAllowed(baseUrl, !targets.allowsPrivate());
        }
        ModelKey key = new ModelKey(baseUrl, CryptoUtils.sha256Hex(creds.getApiKey()), creds.getModel());
        return models.get(key, k -> buildModel(k.baseUrl(), creds));
    }

    /**
     * Options for one chat request. Everything the provider must see in the body goes here and
     * nowhere else — the client's default options are never consulted for a prompt that carries its
     * own (see the class javadoc). {@code extra_body} holds the backend's deep merge of the
     * provider- and per-model extra fields (OpenRouter provider routing and the like); we never log
     * it in full.
     */
    public OpenAiChatOptions requestOptions(LlmCredentials creds, List<ToolCallback> toolCallbacks) {
        Map<String, Object> extraBody = JsonUtils.fromJsonToMap(creds.getExtraBodyJson());
        return OpenAiChatOptions.builder()
                .model(creds.getModel())
                .toolCallbacks(toolCallbacks)
                .extraBody(extraBody.isEmpty() ? null : extraBody)
                .build();
    }

    private OpenAiChatModel buildModel(String baseUrl, LlmCredentials creds) {
        log.info("building chat model: baseUrl={} model={}", baseUrl, creds.getModel());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(creds.getApiKey())
                .model(creds.getModel())
                .timeout(REQUEST_TIMEOUT)
                .customHeaders(requestHeaders(baseUrl))
                .build();
        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(builder -> builder
                        .sslSocketFactory(publicOnlySslSocketFactory())
                        .trustManager(PublicOnlySslSocketFactory.defaultTrustManager()))
                .build();
    }

    /**
     * Spring AI's OpenAI client exposes no DNS hook and follows redirects on its own, so the address
     * is checked where it is still reachable: on the connected socket, before the handshake and
     * therefore before the api key or the prompt go anywhere.
     */
    private PublicOnlySslSocketFactory publicOnlySslSocketFactory() {
        try {
            return new PublicOnlySslSocketFactory(SSLContext.getDefault().getSocketFactory(), targets);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("No default SSL context available", e);
        }
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
