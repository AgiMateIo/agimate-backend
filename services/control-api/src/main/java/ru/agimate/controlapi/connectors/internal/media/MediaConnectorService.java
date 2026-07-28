package ru.agimate.controlapi.connectors.internal.media;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Facade of the media connector: «model as a tool» — generating, editing and reading images with
 * another model, for agents whose chat model cannot do it (docs/connectors/media.md). The tools live
 * in {@link MediaToolService}; model selection, providers and keys are entirely
 * {@code MediaInferenceService}'s business (service/llm) — the connector layer never sees the model
 * registry.
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
        return "Images for agents whose own model can't do them: generating, editing "
                + "and recognising pictures through a separate model.";
    }
}
