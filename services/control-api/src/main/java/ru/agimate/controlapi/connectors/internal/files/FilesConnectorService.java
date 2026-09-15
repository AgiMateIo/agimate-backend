package ru.agimate.controlapi.connectors.internal.files;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;

/**
 * Facade of the files connector: the agent writes, edits, reads and finds the owner's files
 * (docs/connectors/files.md). The tools live in {@link FilesToolService}; versions and the
 * one-writer-at-a-time rule are the file layer's own.
 *
 * <p><b>No owner rule</b>: the boundary is {@code userId} — the same user-wide file space every other
 * connector writes into — and the conversation a listing narrows to is the call's explicit session
 * (see the axis checklist in docs/architecture/connectors.md).
 */
@Component
public class FilesConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = "files";

    public FilesConnectorService(FilesToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Files";
    }

    @Override
    public String connectorDescription() {
        return "Text files the agent writes and keeps editing — documents, reports, HTML pages, "
                + "tables — with every version kept.";
    }
}
