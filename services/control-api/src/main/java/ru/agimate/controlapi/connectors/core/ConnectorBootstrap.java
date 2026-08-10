package ru.agimate.controlapi.connectors.core;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.connectors.core.dto.ContextDirectives;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.connectors.core.jobs.ConnectorJobService;
import ru.agimate.controlapi.database.entities.Connector;
import ru.agimate.controlapi.database.model.ConnectorTraits;
import ru.agimate.controlapi.database.repositories.ConnectorRepository;
import ru.agimate.controlapi.service.seed.ConnectorTexts;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bootstrap of connectors at application start:
 * <ol>
 *   <li>upsert of the static {@code connectors} rows that have no handler ({@code app}, {@code claude-code});</li>
 *   <li>upsert of a {@code connectors} row for every handler in the registry — the code is the source
 *       of truth for name/description/credential_fields/capabilities; name and description pass
 *       through {@link ConnectorTexts} on the way (translating the catalogue under
 *       {@code app.content.language});</li>
 *   <li>re-sync of the existing SYSTEM {@code connector_jobs} rows against {@code getJobs()} — changes
 *       to {@code @Job} (interval, timeout) reach the database without recreating connections.</li>
 * </ol>
 *
 * <p>New jobs are not registered at startup: declarative integration jobs are created on a
 * {@code ConnectorCreatedEvent} (the user adding a connector), and dynamic ones by the agent through
 * tools (e.g. {@code time.schedule}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBootstrap {

    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorJobService jobService;
    private final ConnectorTexts connectorTexts;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        upsertStatic("app", "App", ConnectorTraits.app(),
                "A connected app: the agent calls tools on a computer or phone that runs it "
                        + "— screenshots, files, local actions.");
        upsertStatic("claude-code", "Claude Code", ConnectorTraits.loopback(),
                "Claude Code as the executor: the agent takes the calls and runs them in your environment.");

        for (ConnectorHandler handler : connectorRegistry.getHandlers()) {
            upsertConnector(handler);
        }
        resyncSystemJobs();
        log.info("Connectors bootstrapped: {}", connectorRegistry.getHandlers().size());
    }

    private void resyncSystemJobs() {
        Map<String, Map<String, JobSpec>> declared = new HashMap<>();
        for (ConnectorHandler handler : connectorRegistry.getHandlers()) {
            declared.put(handler.connectorCode(),
                    handler instanceof JobProvider jobProvider ? jobProvider.getJobs() : Map.of());
        }
        jobService.resyncSystemJobs(declared);
    }

    private void upsertConnector(ConnectorHandler handler) {
        Connector connector = connectorRepository.findById(handler.connectorCode())
                .orElseGet(() -> Connector.builder()
                        .code(handler.connectorCode())
                        .build());

        String code = handler.connectorCode();
        connector.setName(connectorTexts.name(code, handler.connectorName()));
        connector.setDescription(connectorTexts.description(code, handler.connectorDescription()));
        connector.setCredentialFields(handler instanceof IntegrationConnectorHandler integration
                ? labelsOf(integration)
                : null);
        connector.applyTraits(handler.traits());
        requireConsistentInstanceBearing(handler, connector);
        requireValidContextDirectives(handler);

        connectorRepository.save(connector);
    }

    /**
     * The row keeps the labels only, not the whole declaration: the column's single reader is
     * {@link Connector#isIntegration()} («are there credentials at all»), and the API assembles the
     * field descriptors from the handler. Storing the richer shape would buy nothing and would cost a
     * migration of rows that this very method rewrites on every startup.
     */
    private static Map<String, String> labelsOf(IntegrationConnectorHandler integration) {
        Map<String, String> labels = new LinkedHashMap<>();
        integration.getCredentialFields().forEach((code, field) -> labels.put(code, field.label()));
        return labels;
    }

    /**
     * Fail-fast validation of the trust fields of {@link ContextDirectives}:
     * {@code presentation=PROMPT} launders the event's text into a trusted block, so it is allowed
     * for internal connectors only (their payload is assembled by our code, authored by the agent or
     * the platform); for an integration, {@code data} comes from the outside world — such a
     * declaration fails the startup. A PROMPT without {@code promptParam} is meaningless, and is also
     * a declaration error.
     */
    private static void requireValidContextDirectives(ConnectorHandler handler) {
        if (!(handler instanceof TriggerProvider triggerProvider)) {
            return;
        }
        triggerProvider.getTriggers().forEach((name, spec) -> {
            ContextDirectives directives = spec.context();
            if (directives == null || directives.presentation() != ContextDirectives.Presentation.PROMPT) {
                return;
            }
            if (!(handler instanceof InternalConnectorHandler)) {
                throw new IllegalStateException("Connector '" + handler.connectorCode() + "', trigger '"
                        + name + "': presentation=PROMPT is allowed for internal connectors only — "
                        + "external trigger data must stay untrusted");
            }
            if (directives.promptParam() == null || directives.promptParam().isBlank()) {
                throw new IllegalStateException("Connector '" + handler.connectorCode() + "', trigger '"
                        + name + "': presentation=PROMPT requires promptParam");
            }
        });
    }

    /**
     * Fail-fast invariant of the derived «instance-bearing» axis: it is pinned down in two places —
     * the handler's type (which the code branches on) and the derivation
     * {@link Connector#isInstanceBearing()} from credentials/APP (the checks performed when a
     * connection is created). A divergence (an integration handler with no credential fields, say)
     * means the new connector was modelled wrongly — fail the startup rather than let the two
     * quietly drift apart.
     */
    private static void requireConsistentInstanceBearing(ConnectorHandler handler, Connector connector) {
        boolean byHandlerType = handler instanceof IntegrationConnectorHandler;
        if (byHandlerType != connector.isInstanceBearing()) {
            throw new IllegalStateException("Connector '" + handler.connectorCode()
                    + "': handler type (integration=" + byHandlerType
                    + ") contradicts derived instance-bearing=" + connector.isInstanceBearing()
                    + " (credentialFields/executionKind) — fix the connector declaration");
        }
    }

    /** Rows with no handler: the source of truth is the same (the code), hence an upsert rather than save-if-absent. */
    private void upsertStatic(String code, String name, ConnectorTraits traits, String description) {
        Connector connector = connectorRepository.findById(code)
                .orElseGet(() -> Connector.builder().code(code).build());
        connector.setName(connectorTexts.name(code, name));
        connector.setDescription(connectorTexts.description(code, description));
        connector.applyTraits(traits);
        connectorRepository.save(connector);
    }
}
