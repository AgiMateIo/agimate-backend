package ru.agimate.controlapi.connectors.internal.web.search;

import ru.agimate.controlapi.connectors.core.ConnectorException;

import java.util.List;

/**
 * Where {@code web_search} gets its results. The one implementation today is
 * {@link YandexSearchBackend}; another provider is another bean — a replacement from outside the
 * project marks its own {@code @Primary}.
 */
public interface WebSearchBackend {

    /**
     * @return at most {@link WebSearchQuery#limit()} hits, empty when nothing was found
     * @throws ConnectorException when the provider is not configured or fails — the message reaches the agent
     */
    List<WebSearchHit> search(WebSearchQuery query);
}
