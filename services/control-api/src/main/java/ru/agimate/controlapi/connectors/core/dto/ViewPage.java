package ru.agimate.controlapi.connectors.core.dto;

import java.util.Map;

/**
 * A view page as a connector serves it. Declared external domains ({@code _meta.ui.csp}) are what
 * the server asks for, not what the installation grants: the serving layer passes them through
 * {@link ru.agimate.controlapi.connectors.core.ViewCspPolicy}, and {@code null} means none.
 *
 * @param permissions   {@code _meta.ui.permissions} the view asks for; {@code null} when absent
 * @param prefersBorder {@code _meta.ui.prefersBorder}; {@code null} when not declared
 * @param csp           declared external domains; {@code null} — none
 */
public record ViewPage(String mimeType, String html, Map<String, Object> permissions, Boolean prefersBorder,
                       ViewCsp csp) {
}
