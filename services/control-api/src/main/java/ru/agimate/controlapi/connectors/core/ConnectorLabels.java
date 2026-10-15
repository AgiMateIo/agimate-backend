package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.enums.ContentLanguage;
import ru.agimate.controlapi.service.seed.ConnectorTexts;

/**
 * A catalogue row's name and description in a given language. The row holds them in
 * {@code app.content.language}, so a translation falls back to the English in the code — the handler's,
 * else {@link StaticConnector}'s — and to the row only for a code that has neither.
 */
@Component
@RequiredArgsConstructor
public class ConnectorLabels {

    private final ConnectorRegistry connectorRegistry;
    private final ConnectorTexts connectorTexts;

    public String name(Connector connector, ContentLanguage language) {
        String source = connectorRegistry.findHandler(connector.getCode())
                .map(ConnectorHandler::connectorName)
                .or(() -> StaticConnector.of(connector.getCode()).map(row -> row.name))
                .orElse(connector.getName());
        return connectorTexts.name(language, connector.getCode(), source);
    }

    public String description(Connector connector, ContentLanguage language) {
        String source = connectorRegistry.findHandler(connector.getCode())
                .map(ConnectorHandler::connectorDescription)
                .or(() -> StaticConnector.of(connector.getCode()).map(row -> row.description))
                .orElse(connector.getDescription());
        return connectorTexts.description(language, connector.getCode(), source);
    }
}
