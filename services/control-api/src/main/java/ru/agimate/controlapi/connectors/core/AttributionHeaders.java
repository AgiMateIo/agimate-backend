package ru.agimate.controlapi.connectors.core;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Единый источник брендовой атрибуции AgiMate в исходящих вызовах коннекторов. Зеркалит
 * app-identity агента (agent-worker {@code ModelFactory#requestHeaders}) на стороне тулов: внешняя
 * сторона видит стабильный {@code User-Agent} вместо дефолтного JDK/OkHttp «Java/xx», а
 * OpenAI-совместимые LLM-провайдеры (media-тул) — ту же OpenRouter app-attribution, что и агент
 * ({@link #llmHeaders(String)}).
 *
 * <p>Только бренд продукта. Идентичность вызывающего (agentId/userId/runId из {@link ConnectorEnv})
 * во внешние заголовки намеренно не кладём — это утечка внутренних id третьим сторонам без
 * потребителя. Для трассировки на своих хопах это делать отдельно и осознанно.
 */
@Component
public class AttributionHeaders {

    private static final String PRODUCT_URL = "https://agimate.io";
    /** Фолбэк, когда build-info недоступен (запуск из IDE / тесты без Gradle-сборки). */
    private static final String FALLBACK_VERSION = "dev";

    /** OpenRouter — единственный провайдер, принимающий app-attribution; детектится по хосту baseUrl. */
    private static final String OPENROUTER_HOST = "openrouter.ai";
    private static final String TITLE = "AgiMate";
    private static final String CATEGORY = "cloud-agent";

    private final String version;
    private final String userAgent;

    @Autowired
    public AttributionHeaders(ObjectProvider<BuildProperties> buildProperties) {
        this(resolveVersion(buildProperties));
    }

    /** Явная версия — для тестов и не-Spring конструирования. */
    public AttributionHeaders(String version) {
        this.version = version;
        this.userAgent = "AgiMate/" + version + " (+" + PRODUCT_URL + ")";
    }

    /** Голая версия продукта — для мест, где нужна только она (например MCP {@code clientInfo.version}). */
    public String version() {
        return version;
    }

    /** Брендовый {@code User-Agent}: {@code AgiMate/<version> (+https://agimate.io)}. */
    public String userAgent() {
        return userAgent;
    }

    /**
     * Заголовки app-атрибуции для исходящего вызова к OpenAI-совместимому LLM-провайдеру — паритет
     * с агентом ({@code ModelFactory#requestHeaders}): брендовый {@code User-Agent} всегда, плюс
     * OpenRouter app-attribution ({@code HTTP-Referer} + title/categories), когда цель — OpenRouter
     * (у него {@code HTTP-Referer} — основной идентификатор приложения, потому без него title/category
     * бессмысленны).
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
