package ru.agimate.controlapi.connectors.internal.web;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.ConnectorEnv;
import ru.agimate.controlapi.connectors.core.ConnectorEnvHolder;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.annotation.Tool;
import ru.agimate.controlapi.connectors.core.annotation.ToolAnnotations;
import ru.agimate.controlapi.connectors.core.annotation.ToolParam;
import ru.agimate.controlapi.connectors.internal.web.page.WebPage;
import ru.agimate.controlapi.connectors.internal.web.page.WebPageReader;
import ru.agimate.controlapi.connectors.internal.web.search.WebSearchBackend;
import ru.agimate.controlapi.connectors.internal.web.search.WebSearchHit;
import ru.agimate.controlapi.connectors.internal.web.search.WebSearchQuery;
import ru.agimate.controlapi.database.repositories.ToolCallLogRepository;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools of the web connector. Both are open-world: the worker wraps their output as untrusted data,
 * which is what a web page and a search snippet are.
 */
@Component
@RequiredArgsConstructor
public class WebToolService {

    static final String SEARCH_TOOL = "web_search";

    static final int MAX_QUERY_LENGTH = 400;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 20;
    static final int RUSSIA = 225;
    static final int DEFAULT_MAX_CHARS = 20_000;
    static final int MIN_MAX_CHARS = 1_000;
    /** The worker cuts any tool output at 64 000 characters; below that the cut stays ours, at a paragraph. */
    static final int MAX_MAX_CHARS = 60_000;

    private final WebSearchBackend searchBackend;
    private final WebPageReader pageReader;
    private final WebProperties properties;
    private final ToolCallLogRepository toolCallLogRepository;

    @Tool(name = SEARCH_TOOL,
            description = "Search the web (Yandex; best for Russian-language sources). Returns results "
                    + "with title, url, a short snippet and the date of the page when known. Snippets are "
                    + "too short to answer from: read the most relevant pages with web_fetch.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = true))
    public Map<String, Object> webSearch(
            @ToolParam("Search query, up to 400 characters; Yandex query operators work, e.g. site:example.ru")
            String query,
            @ToolParam(value = "Number of results, 1-20 (default 10)", required = false) Integer limit,
            @ToolParam(value = "Yandex region id the results are ranked for: 225 — Russia (default), "
                    + "213 — Moscow, 2 — Saint Petersburg, 149 — Belarus, 159 — Kazakhstan", required = false)
            Integer region) {
        if (query == null || query.isBlank()) {
            throw new ConnectorException("query is required");
        }
        String text = query.strip();
        if (text.length() > MAX_QUERY_LENGTH) {
            throw new ConnectorException("query is longer than " + MAX_QUERY_LENGTH + " characters, shorten it");
        }
        requireWithinDailyLimit(ConnectorEnvHolder.current());
        // Weak models send 0 for an optional parameter they mean to skip.
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int regionId = region == null || region <= 0 ? RUSSIA : region;

        List<Map<String, Object>> results = searchBackend.search(new WebSearchQuery(text, size, regionId))
                .stream()
                .map(WebToolService::hit)
                .toList();
        return Map.of("results", results);
    }

    @Tool(name = "web_fetch",
            description = "Read a web page: returns its main text as markdown, with the page's metadata "
                    + "(description, dates, price and the like) first. HTML pages and plain text only. "
                    + "When the page is longer than maxChars the content is cut and truncated is true.",
            annotations = @ToolAnnotations(readOnlyHint = true, openWorldHint = true))
    public Map<String, Object> webFetch(
            @ToolParam("Absolute http(s) URL of the page") String url,
            @ToolParam(value = "Maximum characters of content, 1000-60000 (default 20000)", required = false)
            Integer maxChars) {
        if (url == null || url.isBlank()) {
            throw new ConnectorException("url is required");
        }
        int budget = maxChars == null || maxChars <= 0
                ? DEFAULT_MAX_CHARS
                : Math.clamp(maxChars, MIN_MAX_CHARS, MAX_MAX_CHARS);
        WebPage page = pageReader.read(url, budget);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", page.url());
        if (page.title() != null) {
            result.put("title", page.title());
        }
        result.put("content", page.content());
        result.put("truncated", page.truncated());
        return result;
    }

    /**
     * The platform pays for every search. The count comes from {@code tool_call_logs}, where this very
     * call is already recorded — hence {@code >}.
     */
    private void requireWithinDailyLimit(ConnectorEnv env) {
        int dailyLimit = properties.getSearch().getDailyLimitPerUser();
        if (dailyLimit <= 0 || env.userId() == null) {
            return;
        }
        long used = toolCallLogRepository.countSucceeded(env.userId(), WebConnectorService.CONNECTOR_CODE,
                SEARCH_TOOL, LocalDateTime.now().minusDays(1));
        if (used > dailyLimit) {
            throw new ConnectorException("Daily web search limit reached (" + dailyLimit + " searches per 24 hours). "
                    + "Answer from what you already found, or read known pages with web_fetch.");
        }
    }

    private static Map<String, Object> hit(WebSearchHit hit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", hit.title());
        result.put("url", hit.url());
        result.put("snippet", hit.snippet());
        if (hit.date() != null) {
            result.put("date", hit.date());
        }
        return result;
    }
}
