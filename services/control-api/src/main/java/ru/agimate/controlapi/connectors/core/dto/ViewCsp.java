package ru.agimate.controlapi.connectors.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A view's external domains ({@code _meta.ui.csp}). A connector fills it with what the server
 * declared; {@link ru.agimate.controlapi.connectors.core.ViewCspPolicy} turns that into what the
 * installation grants. A kind with no domains is {@code null}, never an empty list.
 *
 * @param resourceDomains scripts, styles, images, fonts and media
 * @param connectDomains  fetch/XHR/WebSocket
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewCsp(
        List<String> resourceDomains,
        List<String> connectDomains) {
}
