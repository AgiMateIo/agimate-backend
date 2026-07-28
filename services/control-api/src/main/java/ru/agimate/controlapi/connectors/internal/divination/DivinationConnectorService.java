package ru.agimate.controlapi.connectors.internal.divination;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Divination — the internal connector for deterministic esoterica: the Destiny Matrix, numerology and
 * Tarot (the deck comes from a classpath dataset). For the tools see {@link DivinationToolService}.
 */
@Component
public class DivinationConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "divination";

    public DivinationConnectorService(DivinationToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Divination";
    }

    @Override
    public String connectorDescription() {
        return "Deterministic esoterics: Destiny Matrix, numerology and Tarot spreads.";
    }
}
