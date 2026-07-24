package ru.agimate.controlapi.connectors.internal.astro;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Astro — внутренний коннектор настоящих астрономических расчётов для агентов-астрологов:
 * натальная карта, транзиты, синастрия. Эфемериды — Astronomy Engine (MIT), точность
 * ±1 угловая минута; тулы см. {@link AstroToolService}.
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
