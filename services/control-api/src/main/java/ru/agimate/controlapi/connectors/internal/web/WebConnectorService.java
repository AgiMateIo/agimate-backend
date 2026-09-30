package ru.agimate.controlapi.connectors.internal.web;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Facade of the web connector: search and reading pages, tools in {@link WebToolService}. The search
 * key is the operator's; without it {@code web_search} says so and {@code web_fetch} keeps working.
 * See docs/connectors/web.md.
 */
@Component
public class WebConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "web";

    public WebConnectorService(WebToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Web";
    }

    @Override
    public String connectorDescription() {
        return "Web search and reading pages: the agent finds sources, reads them and answers with links.";
    }
}
