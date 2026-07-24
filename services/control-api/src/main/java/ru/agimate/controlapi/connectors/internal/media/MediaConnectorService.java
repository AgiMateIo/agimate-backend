package ru.agimate.controlapi.connectors.internal.media;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Фасад media-коннектора: «модель как инструмент» — генерация/редактирование/чтение изображений
 * чужой моделью для агентов, чья chat-модель этого не умеет (docs/connectors/media.md). Тулы —
 * в {@link MediaToolService}; выбор модели, провайдеры и ключи целиком за
 * {@code MediaInferenceService} (service/llm) — коннекторный слой реестра моделей не видит.
 */
@Component
public class MediaConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "media";

    public MediaConnectorService(MediaToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Media";
    }

    @Override
    public String connectorDescription() {
        return "Картинки для агентов, чья модель этого не умеет: генерация, редактирование "
                + "и распознавание изображений отдельной моделью.";
    }
}
