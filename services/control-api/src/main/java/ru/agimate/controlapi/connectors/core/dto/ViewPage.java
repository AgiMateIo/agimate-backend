package ru.agimate.controlapi.connectors.core.dto;

import java.util.Map;

/**
 * A view page as a connector serves it. Declared external domains ({@code _meta.ui.csp}) have no field:
 * a view gets none of them.
 *
 * @param permissions   {@code _meta.ui.permissions} the view asks for; {@code null} when absent
 * @param prefersBorder {@code _meta.ui.prefersBorder}; {@code null} when not declared
 */
public record ViewPage(String mimeType, String html, Map<String, Object> permissions, Boolean prefersBorder) {
}
