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

import java.util.HashMap;
import java.util.Map;

/**
 * Бутстрап коннекторов при старте приложения:
 * <ol>
 *   <li>статические строки {@code connectors} ({@code app}, {@code claude-code}) — только если отсутствуют;</li>
 *   <li>upsert строки {@code connectors} для каждого handler'а из registry — код-источник истины
 *       для name/credential_fields/capabilities; description/features не затираются;</li>
 *   <li>пересинк существующих SYSTEM-строк {@code connector_jobs} с {@code getJobs()} — изменения
 *       {@code @Job} (интервал/timeout) доезжают до БД без пересоздания подключений.</li>
 * </ol>
 *
 * <p>Новые задачи на старте не регистрируются: декларативные таски интеграций заводятся
 * по {@code ConnectorCreatedEvent} (добавление коннектора пользователем), динамические — агентом
 * через тулы (например {@code time.schedule}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectorBootstrap {

    private final ConnectorRepository connectorRepository;
    private final ConnectorRegistry connectorRegistry;
    private final ConnectorJobService jobService;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        saveIfAbsent(buildStatic("app", "App", ConnectorTraits.device()));
        saveIfAbsent(buildStatic("claude-code", "Claude Code", ConnectorTraits.loopback()));

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

        connector.setName(handler.connectorName());
        connector.setCredentialFields(handler instanceof IntegrationConnectorHandler integration
                ? integration.getCredentialFields()
                : null);
        connector.applyTraits(handler.traits());
        requireConsistentInstanceBearing(handler, connector);
        requireValidContextDirectives(handler);

        connectorRepository.save(connector);
    }

    /**
     * Fail-fast валидация trust-полей {@link ContextDirectives}: {@code presentation=PROMPT}
     * отмывает текст события в trusted-блок, поэтому разрешён только internal-коннекторам
     * (их payload собирает наш код, авторство — агент/платформа); у интеграции {@code data}
     * приходит из внешнего мира — такая декларация роняет старт. PROMPT без {@code promptParam}
     * бессмыслен — тоже ошибка декларации.
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
     * Fail-fast инвариант выводимой оси «экземплярность»: у неё две фиксации — тип хендлера
     * (ветвления кода) и деривация {@link Connector#isInstanceBearing()} из credentials/DEVICE
     * (проверки при создании connection). Расхождение (например, integration-хендлер без
     * credential-полей) означает ошибку моделирования нового коннектора — роняем старт, а не
     * даём фиксациям молча разъехаться.
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

    private static Connector buildStatic(String code, String name, ConnectorTraits traits) {
        Connector connector = Connector.builder().code(code).name(name).build();
        connector.applyTraits(traits);
        return connector;
    }

    private void saveIfAbsent(Connector connector) {
        if (!connectorRepository.existsById(connector.getCode())) {
            connectorRepository.save(connector);
            log.info("Created connector: {}", connector.getCode());
        }
    }
}
