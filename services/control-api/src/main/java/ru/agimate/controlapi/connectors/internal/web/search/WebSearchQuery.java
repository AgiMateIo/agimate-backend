package ru.agimate.controlapi.connectors.internal.web.search;

/**
 * @param limit  already clamped by the caller
 * @param region a Yandex region id; a provider without regions ignores it
 */
public record WebSearchQuery(String text, int limit, int region) {
}
