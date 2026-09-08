package ru.agimate.controlapi.connectors.internal.toolloader;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.service.runcontext.RunCatalog;

/**
 * Facade of the tool loader: the tool-schema half of progressive disclosure
 * ({@code docs/decisions/progressive-disclosure.md}). A mode row per user, bound through the
 * {@code tool-loader} skill like memory; its presence in a run's scope is what switches the LAZY
 * axis of tools on for that run — without it every schema ships up front. The one tool,
 * {@code load_tools}, lives in {@link ToolLoaderToolService}.
 */
@Component
public class ToolLoaderConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = RunCatalog.TOOL_LOADER;

    public ToolLoaderConnectorService(ToolLoaderToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Tool Loader";
    }

    @Override
    public String connectorDescription() {
        return "Deferred tools: the agent sees them listed by name and summary and loads the full "
                + "definitions of the ones it needs right now.";
    }
}
