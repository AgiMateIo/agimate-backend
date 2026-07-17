package ru.agimate.controlapi.connectors.core.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.JobSpec;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API записи в {@code connector_jobs}. Лежит между listener'ами/bootstrap'ом и БД.
 *
 * <p>Pull‑модель не нуждается в событиях: scheduler читает БД на каждом тике, поэтому новые/удалённые
 * задачи появляются в работе автоматически в пределах одного poll‑интервала.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorJobService {

    private static final int LAST_ERROR_LIMIT = 4_000;

    private final ConnectorJobRepository connectorJobRepository;

    /**
     * Не {@code readOnly} — внутри pickup делает UPDATE ... RETURNING. Дефолтный {@code REQUIRED}
     * propagation у repo impl присоединился бы к внешней readOnly‑транзакции и упал на PG уровне.
     */
    @Transactional
    public List<ConnectorJob> claimReady(int batchSize) {
        return connectorJobRepository.claimReady(LocalDateTime.now(), batchSize);
    }

    /**
     * Создаёт или обновляет строку по бизнес‑ключу {@code (connectorCode, connectionId, name)}.
     * Новая строка получает {@code status=PENDING}, {@code next_run_at=now()} — scheduler
     * подхватит её на ближайшем тике. COMPLETED-строка (выполненный ONETIME) взводится заново.
     *
     * <p>{@code REQUIRES_NEW} нужен потому, что метод вызывается из
     * {@code @TransactionalEventListener(AFTER_COMMIT)} — там outer‑транзакция уже committed,
     * но её EntityManagerHolder ещё привязан к потоку. REQUIRED participate'нулся бы к мёртвой
     * транзакции и упал на «No active transaction». REQUIRES_NEW suspend'ит stale holder и
     * стартует чистую tx.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConnectorJob upsert(String connectorCode, String connectionId, UUID userId, JobSpec spec) {
        return doUpsert(connectorCode, connectionId, userId, spec);
    }

    /**
     * Приводит набор SYSTEM-задач connectionId в соответствие с декларацией коннектора: upsert всех
     * актуальных + удаление строк, чьи {@code name} больше не возвращаются {@code getJobs()}.
     * Динамические задачи (USER/AGENT) на этом connectionId пересинк не трогает.
     * {@code REQUIRES_NEW} — по той же причине, что и {@link #upsert}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncConnectionJobs(String connectorCode, String connectionId, UUID userId,
                             Collection<JobSpec> specs) {
        if (specs.isEmpty()) {
            connectorJobRepository.deleteSystemByConnectionId(connectorCode, connectionId);
            return;
        }
        for (JobSpec spec : specs) {
            doUpsert(connectorCode, connectionId, userId, spec);
        }
        connectorJobRepository.deleteStale(connectorCode, connectionId,
                specs.stream().map(JobSpec::name).toList());
    }

    /**
     * Удаляет все строки connectionId, включая динамические (USER/AGENT) — вызывается при удалении
     * интеграции, когда без credentials они всё равно неисполнимы.
     * {@code REQUIRES_NEW} — по той же причине, что и {@link #upsert}: вызов из AFTER_COMMIT listener'а.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteByConnectionId(String connectorCode, String connectionId) {
        return connectorJobRepository.deleteByConnectionId(connectorCode, connectionId);
    }

    /**
     * Завершает текущую итерацию: переводит в {@code PENDING}, очищает lease, выставляет
     * {@code next_run_at}. {@code lastError == null} означает успех.
     */
    @Transactional
    public void complete(UUID taskId, LocalDateTime nextRunAt, String lastError) {
        connectorJobRepository.complete(taskId, nextRunAt, trimError(lastError));
    }

    /**
     * Startup-пересинк существующих SYSTEM-строк с декларацией коннекторов ({@code getJobs()}):
     * изменение {@code @Job} (интервал/timeout/config) попадает в БД без пересоздания подключения;
     * строки, чьи имена больше не декларируются (например, смена режима telegram polling→webhook),
     * удаляются. Новые строки не создаёт — их заводят lifecycle-события подключений. Спека
     * обновляется точечным UPDATE — status/lease конкурентно пишет scheduler (свой и соседних нод).
     */
    @Transactional
    public void resyncSystemJobs(Map<String, Map<String, JobSpec>> declaredByConnector) {
        for (ConnectorJob row : connectorJobRepository.findByKind(ConnectorJobKind.SYSTEM)) {
            Map<String, JobSpec> declared = declaredByConnector.get(row.getConnectorCode());
            if (declared == null) {
                log.warn("System job {}/{}/{}: no handler in registry — left as is",
                        row.getConnectorCode(), row.getConnectionId(), row.getName());
                continue;
            }
            JobSpec spec = declared.get(row.getName());
            if (spec == null) {
                connectorJobRepository.deleteById(row.getId());
                log.info("Removed undeclared system job {}/{}/{}",
                        row.getConnectorCode(), row.getConnectionId(), row.getName());
                continue;
            }
            connectorJobRepository.updateSpec(
                    row.getId(), spec.type(), spec.config(), spec.args(), spec.timeoutSeconds());
        }
    }

    /**
     * Возвращает claim'нутую этой нодой строку в очередь при остановке приложения: PENDING,
     * запуск сразу после рестарта. Только для RUNNING — финализированные в гонке строки не трогает.
     */
    @Transactional
    public void release(UUID taskId) {
        connectorJobRepository.release(taskId, LocalDateTime.now());
    }

    /** Финализирует успешно выполненный ONETIME: {@code status=COMPLETED}, без следующего запуска. */
    @Transactional
    public void markCompleted(UUID taskId, String lastError) {
        connectorJobRepository.markCompleted(taskId, trimError(lastError));
    }

    // ===== Динамические задачи, запланированные агентом (time.schedule и т.п.) =====

    /**
     * Планирует динамическую задачу агента ({@code kind=AGENT}): INSERT новой строки (в отличие
     * от {@link #upsert} — бизнес-ключ на неё не действует, на агента их может быть много).
     * {@code firstRunAt} — момент первого срабатывания (для ONETIME это и есть единственный запуск).
     */
    @Transactional
    public ConnectorJob schedule(String connectorCode, String connectionId, UUID userId, UUID agentId,
                                 UUID channelId, JobSpec spec, LocalDateTime firstRunAt) {
        if (agentId == null) {
            throw new ConnectorException("Dynamic task requires an initiating agent");
        }
        ConnectorJob row = ConnectorJob.builder()
                .connectorCode(connectorCode)
                .connectionId(connectionId)
                .userId(userId)
                .agentId(agentId)
                .channelId(channelId)
                .kind(ConnectorJobKind.AGENT)
                .name(spec.name())
                .type(spec.type())
                .config(spec.config())
                .args(spec.args())
                .timeoutSeconds(spec.timeoutSeconds())
                .status(ConnectorJobStatus.PENDING)
                .nextRunAt(firstRunAt)
                .build();
        return connectorJobRepository.save(row);
    }

    /** Активные (не COMPLETED) задачи агента — для list. */
    public List<ConnectorJob> findActiveByAgent(String connectorCode, UUID userId, UUID agentId) {
        return connectorJobRepository.findActiveByAgent(connectorCode, userId, agentId);
    }

    /** Отменяет задачу агента с проверкой владельца; {@code true} — действительно удалена. */
    @Transactional
    public boolean cancel(String connectorCode, UUID userId, UUID agentId, UUID taskId) {
        return connectorJobRepository.deleteOwned(taskId, connectorCode, userId, agentId) > 0;
    }

    private ConnectorJob doUpsert(String connectorCode, String connectionId, UUID userId, JobSpec spec) {
        ConnectorJob row = connectorJobRepository.findByBusinessKey(connectorCode, connectionId, spec.name())
                .orElseGet(() -> ConnectorJob.builder()
                        .connectorCode(connectorCode)
                        .connectionId(connectionId)
                        .kind(ConnectorJobKind.SYSTEM)
                        .name(spec.name())
                        .status(ConnectorJobStatus.PENDING)
                        .nextRunAt(LocalDateTime.now())
                        .build());

        if (row.getStatus() == ConnectorJobStatus.COMPLETED) {
            row.setStatus(ConnectorJobStatus.PENDING);
            row.setNextRunAt(LocalDateTime.now());
        }
        row.setUserId(userId);
        row.setType(spec.type());
        row.setConfig(spec.config());
        row.setArgs(spec.args());
        row.setTimeoutSeconds(spec.timeoutSeconds());

        return connectorJobRepository.save(row);
    }

    private static String trimError(String lastError) {
        return (lastError == null || lastError.length() <= LAST_ERROR_LIMIT)
                ? lastError
                : lastError.substring(0, LAST_ERROR_LIMIT);
    }
}
