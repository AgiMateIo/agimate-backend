package ru.agimate.controlapi.connectors.internal.web.page;

import org.jsoup.nodes.Element;

import java.util.Map;

/**
 * @param title    {@code null} — the page has none
 * @param body     the main text's subtree; {@code null} — there is no text worth reading (a page
 *                 rendered by JavaScript), and the metadata is all there is
 * @param metadata description, author, dates, {@code og:*}, JSON-LD — in the page's order
 */
public record ExtractedContent(String title, Element body, Map<String, String> metadata) {
}
