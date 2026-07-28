package ru.agimate.controlapi.connectors.core;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * The single source of AgiMate brand attribution in connectors' outbound calls. Mirrors the agent's
 * app identity (agent-worker {@code ModelFactory#requestHeaders}) on the tool side: the far end sees
 * a stable {@code User-Agent} instead of the default JDK/OkHttp «Java/xx», and OpenAI-compatible LLM
 * providers (the media tool) see the same OpenRouter app attribution the agent sends
 * ({@link #llmHeaders(String)}).
 *
 * <p>Product branding only. The caller's identity (agentId/userId/runId from {@link ConnectorEnv})
 * is deliberately kept out of outbound headers — that would leak internal ids to third parties with
 * nobody to consume them. Tracing across our own hops is a separate, deliberate decision.
 */
@Component
public class AttributionHeaders {

    private static final String PRODUCT_URL = "https://agimate.io";
    /** Fallback for when build-info is unavailable (running from an IDE, or tests without a Gradle build). */
    private static final String FALLBACK_VERSION = "dev";

    /** OpenRouter is the only provider that accepts app attribution; detected by the baseUrl's host. */
    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String TITLE = "AgiMate";
    private static final String CATEGORY = "cloud-agent";

    private final String version;
    private final String userAgent;

    @Autowired
    public AttributionHeaders(ObjectProvider<BuildProperties> buildProperties) {
        this(resolveVersion(buildProperties));
    }

    /** Explicit version — for tests and non-Spring construction. */
    public AttributionHeaders(String version) {
        this.version = version;
        this.userAgent = "AgiMate/" + version + " (+" + PRODUCT_URL + ")";
    }

    /** The bare product version — for places that need only that (e.g. MCP {@code clientInfo.version}). */
    public String version() {
        return version;
    }

    /** Branded {@code User-Agent}: {@code AgiMate/<version> (+https://agimate.io)}. */
    public String userAgent() {
        return userAgent;
    }

    /**
     * App attribution headers for an outbound call to an OpenAI-compatible LLM provider — parity with
     * the agent ({@code ModelFactory#requestHeaders}): the branded {@code User-Agent} always, plus
     * OpenRouter app attribution ({@code HTTP-Referer} + title/categories) when the target is
     * OpenRouter (there {@code HTTP-Referer} is the application's primary identifier, which is why
     * title and category are meaningless without it).
     */
    public Map<String, String> llmHeaders(String baseUrl) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", userAgent);
        if (baseUrl != null && baseUrl.contains(OPENROUTER_HOST)) {
            headers.put("HTTP-Referer", PRODUCT_URL);
            headers.put("X-OpenRouter-Title", TITLE);
            headers.put("X-OpenRouter-Categories", CATEGORY);
        }
        return headers;
    }

    private static String resolveVersion(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties props = buildProperties.getIfAvailable();
        return props != null ? props.getVersion() : FALLBACK_VERSION;
    }
}
