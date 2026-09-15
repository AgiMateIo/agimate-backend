package ru.agimate.agentworker.llm;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.openai.core.http.ProxyAuthenticator;
import okhttp3.Interceptor;
import okhttp3.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.common.net.EgressProxy;
import ru.agimate.common.net.OutboundTrust;
import ru.agimate.common.net.PublicOnlySslSocketFactory;
import ru.agimate.common.net.PublicTargets;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

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
 * connections after ~5 min and dispatcher threads after 60 s). The call is capped at
 * {@code agent.llm.call-timeout} (the SDK default is 10 minutes, which would pin an
 * {@code LlmCall} semaphore slot for that long on a hung provider).
 */
@Component
@Slf4j
public class ModelFactory {

    private static final Set<String> OPENAI_COMPATIBLE =
            Set.of("openai", "openai_compatible", "openai-compatible");

    /** Where Spring AI sends a provider without a base url — needed to decide on the proxy for it. */
    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";

    /** OpenRouter base URLs contain this host; app-attribution headers are sent only to it. */
    private static final String OPENROUTER_HOST = "openrouter.ai";

    /**
     * Provider-side retries, off. The SDK's own retry loop ({@code RetryingHttpClient}: 408/409/429/5xx
     * and {@code IOException}) nests inside {@link ru.agimate.agentworker.workers.run.LlmCall}'s, and
     * Spring AI defaults it to 3 — four attempts of ours became sixteen paid requests, each of which
     * the model finished and billed. Everything the second layer did we do ourselves and can see:
     * classification, {@code Retry-After}, backoff, the log line.
     */
    private static final int PROVIDER_RETRIES = 0;

    private final AgentProperties.App app;

    /** Ceiling on the whole call — see {@link AgentProperties.Llm#getCallTimeout()}. */
    private final Duration callTimeout;

    /**
     * The longest silence on the wire any waiting budget tolerates, handed to OkHttp as the read
     * timeout — see {@link AgentProperties.Llm#getFirstChunkTimeout()}. Needed next to the stream-level
     * budgets because they alone do not free the socket: cancelling the subscription asks the SDK to
     * close the stream, and the SDK's close waits for the reader thread, which is blocked in
     * {@code readLine} until the provider sends something or the socket dies. Only a timeout inside
     * the read itself ends that wait, so a silent attempt returns at this budget instead of lingering
     * to the call ceiling with the provider still generating.
     */
    private final Duration readTimeout;

    /**
     * The base url belongs to whoever created the provider, so the call is a request forgery target:
     * it leaves our network with an api key on it, and its answer lands in the agent's history where
     * whoever wrote the prompt can read it. Both halves of the guard are needed — the url is vetted
     * per call below, and {@link PublicOnlySslSocketFactory} checks the connected socket, which is
     * what survives a name that answers differently the second time it is asked.
     */
    private final PublicTargets targets;

    /** Whose signature is accepted. Both halves go to OkHttp from one instance — see {@link OutboundTrust}. */
    private final OutboundTrust trust;

    /**
     * Decided per model rather than per request: a model is built for one base url, which is already in
     * the cache key, and the proxy changes only with a restart.
     */
    private final EgressProxy proxy;

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

    public ModelFactory(AgentProperties props, OutboundTrust trust, EgressProxy proxy) {
        this.app = props.getApp();
        this.callTimeout = props.getLlm().getCallTimeout();
        this.readTimeout = props.getLlm().getFirstChunkTimeout();
        this.targets = new PublicTargets(props.getNet().isAllowPrivateTargets(), proxy);
        this.trust = trust;
        this.proxy = proxy;
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
                // The ceiling has to ride the per-call options: Spring AI builds the SDK's request
                // options from the prompt's options alone, and its builder fills an unset timeout with
                // AbstractOpenAiOptions.DEFAULT_TIMEOUT — 60 s. A ceiling set only on the client's
                // default options never reaches the wire; every call was cut at the minute until
                // this line (the 2026-09-09 incident). It lands on OkHttp's callTimeout — the whole
                // call, wall clock. The two budgets that measure waiting live on the stream
                // ({@code LlmCall}), because a pause between chunks is not expressible here; the read
                // timeout that frees the socket under them is set by the interceptor below.
                .timeout(callTimeout)
                .build();
    }

    private OpenAiChatModel buildModel(String baseUrl, LlmCredentials creds) {
        log.info("building chat model: baseUrl={} model={}", baseUrl, creds.getModel());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(creds.getApiKey())
                .model(creds.getModel())
                .maxRetries(PROVIDER_RETRIES)
                .customHeaders(requestHeaders(baseUrl))
                .build();
        boolean proxied = proxy.covers(URI.create(baseUrl != null ? baseUrl : DEFAULT_BASE_URL).getHost());
        return OpenAiChatModel.builder()
                .options(options)
                .httpClientBuilderCustomizer(builder -> {
                    builder.sslSocketFactory(publicOnlySslSocketFactory())
                            .trustManager(trust.manager())
                            .interceptor(this::withReadTimeout);
                    if (proxied) {
                        builder.proxy(proxy.proxy()).proxyAuthenticator(proxyAuthenticator());
                    }
                })
                .build();
    }

    /**
     * Basic credentials offered once. The SDK's own {@code basic} answers every {@code 407} with the same
     * header, so a wrong password made OkHttp open the tunnel 21 times and fail with «Too many tunnel
     * connections» — inside each of {@code LlmCall}'s attempts, and without a word about credentials.
     */
    private ProxyAuthenticator proxyAuthenticator() {
        ProxyAuthenticator basic = ProxyAuthenticator.basic(proxy.username(), proxy.password());
        return (via, request, response) -> request.headers().values("Proxy-Authorization").isEmpty()
                ? basic.authenticate(via, request, response)
                : Optional.empty();
    }

    /**
     * Sets the read timeout per call. An application interceptor, because the client copy Spring AI
     * makes for each call overwrites the builder's timeouts from the SDK's single {@code Timeout}
     * (read falls back to the call ceiling there); the interceptor runs after that and wins. Pinned
     * against a live socket in {@code LlmCallTest}.
     */
    private Response withReadTimeout(Interceptor.Chain chain) throws IOException {
        return chain.withReadTimeout((int) readTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .proceed(chain.request());
    }

    /**
     * Spring AI's OpenAI client exposes no DNS hook and follows redirects on its own, so the address
     * is checked where it is still reachable: on the connected socket, before the handshake and
     * therefore before the api key or the prompt go anywhere.
     */
    private PublicOnlySslSocketFactory publicOnlySslSocketFactory() {
        return new PublicOnlySslSocketFactory(trust.socketFactory(), targets);
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
