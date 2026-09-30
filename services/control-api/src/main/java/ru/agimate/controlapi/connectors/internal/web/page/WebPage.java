package ru.agimate.controlapi.connectors.internal.web.page;

/**
 * @param url       where the page ended up after redirects
 * @param title     {@code null} — the page has none
 * @param content   markdown: the metadata block first, then the main text
 * @param truncated the content was cut to the requested size
 */
public record WebPage(String url, String title, String content, boolean truncated) {
}
