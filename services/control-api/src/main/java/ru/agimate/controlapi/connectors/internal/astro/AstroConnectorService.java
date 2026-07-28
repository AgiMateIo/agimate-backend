package ru.agimate.controlapi.connectors.internal.astro;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Astro — the internal connector for genuine astronomical computation, for astrologer agents: natal
 * chart, transits, synastry. The ephemerides come from Astronomy Engine (MIT), accurate to ±1 arc
 * minute; for the tools see {@link AstroToolService}.
 */
@Component
public class AstroConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "astro";

    public AstroConnectorService(AstroToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Astro";
    }

    @Override
    public String connectorDescription() {
        return "Настоящие астрономические расчёты: натальная карта, транзиты и синастрия "
                + "по эфемеридам с точностью до угловой минуты.";
    }
}
