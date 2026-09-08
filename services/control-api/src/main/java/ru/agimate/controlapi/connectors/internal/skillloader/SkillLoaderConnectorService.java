package ru.agimate.controlapi.connectors.internal.skillloader;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.BaseConnectorHandler;
import ru.agimate.controlapi.connectors.core.InternalConnectorHandler;
import ru.agimate.controlapi.service.runcontext.RunCatalog;

/**
 * Facade of the skill loader: the skill-body half of progressive disclosure
 * ({@code docs/decisions/progressive-disclosure.md}). A mode row per user, bound through the
 * {@code skill-loader} skill like memory; its presence in a run's scope is what switches the LAZY
 * axis of skills on for that run — without it every body ships in the prompt. The one tool,
 * {@code load_skill}, lives in {@link SkillLoaderToolService}.
 */
@Component
public class SkillLoaderConnectorService extends BaseConnectorHandler implements InternalConnectorHandler {

    public static final String CONNECTOR_CODE = RunCatalog.SKILL_LOADER;

    public SkillLoaderConnectorService(SkillLoaderToolService toolService) {
        super(toolService);
    }

    @Override
    public String connectorCode() {
        return CONNECTOR_CODE;
    }

    @Override
    public String connectorName() {
        return "Skill Loader";
    }

    @Override
    public String connectorDescription() {
        return "Deferred skills: the agent sees them listed and loads the full instructions of the "
                + "ones it needs right now.";
    }
}
