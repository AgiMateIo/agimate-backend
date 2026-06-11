package ru.agimate.controlapi.connectors.core.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskKind;
import ru.agimate.controlapi.database.enums.ConnectorTaskStatus;
import ru.agimate.controlapi.database.repositories.ConnectorTaskRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * API записи в {@code connector_tasks}. Лежит между listener'ами/bootstrap'ом и БД.
 *
 * <p>Pull‑модель не нуждается в событиях: scheduler читает БД на каждом тике, поэтому новые/удалённые
 * задачи появляются в работе автоматически в пределах одного poll‑интервала.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorTaskService {

    private static final int LAST_ERROR_LIMIT = 4_000;

    private final ConnectorTaskRepository connectorTaskRepository;

    /**
     * Не {@code readOnly} — внутри pickup делает UPDATE ... RETURNING. Дефолтный {@code REQUIRED}
     * propagation у repo impl присоединился бы к внешней readOnly‑транзакции и упал на PG уровне.
     */
    @Transactional
    public List<ConnectorTask> claimReady(int batchSize) {
        return connectorTaskRepository.claimReady(LocalDateTime.now(), batchSize);
    }

    /**
     * Создаёт или обновляет строку по бизнес‑ключу {@code (connectorCode, identity, taskName)}.
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
    public ConnectorTask upsert(String connectorCode, String identity, UUID userId, TaskSpecification spec) {
        return doUpsert(connectorCode, identity, userId, spec);
    }

    /**
     * Приводит набор SYSTEM-задач identity в соответствие с декларацией коннектора: upsert всех
     * актуальных + удаление строк, чьи {@code task_name} больше не возвращаются {@code getTasks()}.
     * Динамические задачи (USER/AGENT) на этом identity пересинк не трогает.
     * {@code REQUIRES_NEW} — по той же причине, что и {@link #upsert}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncIdentity(String connectorCode, String identity, UUID userId,
                             Collection<TaskSpecification> specs) {
        if (specs.isEmpty()) {
            connectorTaskRepository.deleteSystemByIdentity(connectorCode, identity);
            return;
        }
        for (TaskSpecification spec : specs) {
            doUpsert(connectorCode, identity, userId, spec);
        }
        connectorTaskRepository.deleteStale(connectorCode, identity,
                specs.stream().map(TaskSpecification::name).toList());
    }

    /**
     * Удаляет все строки identity, включая динамические (USER/AGENT) — вызывается при удалении
     * интеграции, когда без credentials они всё равно неисполнимы.
     * {@code REQUIRES_NEW} — по той же причине, что и {@link #upsert}: вызов из AFTER_COMMIT listener'а.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteByIdentity(String connectorCode, String identity) {
        return connectorTaskRepository.deleteByIdentity(connectorCode, identity);
    }

    /**
     * Завершает текущую итерацию: переводит в {@code PENDING}, очищает lease, выставляет
     * {@code next_run_at}. {@code lastError == null} означает успех.
     */
    @Transactional
    public void complete(UUID taskId, LocalDateTime nextRunAt, String lastError) {
        connectorTaskRepository.complete(taskId, nextRunAt, trimError(lastError));
    }

    /** Финализирует успешно выполненный ONETIME: {@code status=COMPLETED}, без следующего запуска. */
    @Transactional
    public void markCompleted(UUID taskId, String lastError) {
        connectorTaskRepository.markCompleted(taskId, trimError(lastError));
    }

    // ===== Динамические задачи, запланированные агентом (time.schedule и т.п.) =====

    /**
     * Планирует динамическую задачу агента ({@code kind=AGENT}): INSERT новой строки (в отличие
     * от {@link #upsert} — бизнес-ключ на неё не действует, на агента их может быть много).
     * {@code firstRunAt} — момент первого срабатывания (для ONETIME это и есть единственный запуск).
     */
    @Transactional
    public ConnectorTask schedule(String connectorCode, String identity, UUID userId, UUID agentId,
                                  TaskSpecification spec, LocalDateTime firstRunAt) {
        if (agentId == null) {
            throw new ConnectorException("Dynamic task requires an initiating agent");
        }
        ConnectorTask row = ConnectorTask.builder()
                .connectorCode(connectorCode)
                .identity(identity)
                .userId(userId)
                .agentId(agentId)
                .kind(ConnectorTaskKind.AGENT)
                .taskName(spec.name())
                .taskType(spec.taskType())
                .taskConfig(spec.taskConfig())
                .taskArgs(spec.taskArgs())
                .timeoutSeconds(spec.timeoutSeconds())
                .status(ConnectorTaskStatus.PENDING)
                .nextRunAt(firstRunAt)
                .build();
        return connectorTaskRepository.save(row);
    }

    /** Активные (не COMPLETED) задачи агента — для list. */
    public List<ConnectorTask> findActiveByAgent(String connectorCode, UUID userId, UUID agentId) {
        return connectorTaskRepository.findActiveByAgent(connectorCode, userId, agentId);
    }

    /** Отменяет задачу агента с проверкой владельца; {@code true} — действительно удалена. */
    @Transactional
    public boolean cancel(String connectorCode, UUID userId, UUID agentId, UUID taskId) {
        return connectorTaskRepository.deleteOwned(taskId, connectorCode, userId, agentId) > 0;
    }

    private ConnectorTask doUpsert(String connectorCode, String identity, UUID userId, TaskSpecification spec) {
        ConnectorTask row = connectorTaskRepository.findByBusinessKey(connectorCode, identity, spec.name())
                .orElseGet(() -> ConnectorTask.builder()
                        .connectorCode(connectorCode)
                        .identity(identity)
                        .kind(ConnectorTaskKind.SYSTEM)
                        .taskName(spec.name())
                        .status(ConnectorTaskStatus.PENDING)
                        .nextRunAt(LocalDateTime.now())
                        .build());

        if (row.getStatus() == ConnectorTaskStatus.COMPLETED) {
            row.setStatus(ConnectorTaskStatus.PENDING);
            row.setNextRunAt(LocalDateTime.now());
        }
        row.setUserId(userId);
        row.setTaskType(spec.taskType());
        row.setTaskConfig(spec.taskConfig());
        row.setTaskArgs(spec.taskArgs());
        row.setTimeoutSeconds(spec.timeoutSeconds());

        return connectorTaskRepository.save(row);
    }

    private static String trimError(String lastError) {
        return (lastError == null || lastError.length() <= LAST_ERROR_LIMIT)
                ? lastError
                : lastError.substring(0, LAST_ERROR_LIMIT);
    }
}
