package ru.agimate.controlapi.connectors.internal.web.page;

import org.jsoup.nodes.Element;

/**
 * A DOM subtree as markdown. Links come out absolute, so the element must carry its document's base URI.
 */
public interface MarkdownConverter {

    String convert(Element root);
}
