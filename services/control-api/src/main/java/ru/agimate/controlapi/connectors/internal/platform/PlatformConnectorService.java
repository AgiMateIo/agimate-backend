package ru.agimate.controlapi.connectors.internal.platform;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Фасад platform-коннектора: тулы управления платформой живут в {@link PlatformToolService}
 * (диспатч рефлексией через {@link BaseConnectorHandler}). Триггеров/джоб нет — коннектор чисто
 * командный. Привязывается к мета-агенту скиллом {@code platform-admin} ({@code connectorCodes:
 * [platform]}); владелец операций — {@code env.userId} (человек-владелец агента).
 */
@Component
public class PlatformConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "platform";

    public PlatformConnectorService(PlatformToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Platform";
    }

    @Override
    public String connectorDescription() {
        return "Управление платформой из диалога: агент заводит других агентов, скиллы, "
                + "подключения и расписания вместо ручной настройки в интерфейсе.";
    }
}
