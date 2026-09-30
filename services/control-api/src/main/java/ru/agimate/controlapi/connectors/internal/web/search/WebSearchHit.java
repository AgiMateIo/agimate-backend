package ru.agimate.controlapi.connectors.internal.web.search;

/**
 * @param snippet plain text, highlighting already stripped
 * @param date    ISO date of the page's last change as the provider knows it; {@code null} — unknown
 */
public record WebSearchHit(String title, String url, String snippet, String date) {
}
