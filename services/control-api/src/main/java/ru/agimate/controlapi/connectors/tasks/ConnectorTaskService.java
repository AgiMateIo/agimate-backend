package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.dto.TaskSpecification;
import ru.agimate.controlapi.database.entities.ConnectorTask;
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

    private final ConnectorTaskRepository repository;

    /**
     * Не {@code readOnly} — внутри pickup делает UPDATE ... RETURNING. Дефолтный {@code REQUIRED}
     * propagation у repo impl присоединился бы к внешней readOnly‑транзакции и упал на PG уровне.
     */
    @Transactional
    public List<ConnectorTask> claimReady(int batchSize) {
        return repository.claimReady(LocalDateTime.now(), batchSize);
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
    public ConnectorTask upsert(String connectorCode, String identity, TaskSpecification spec) {
        return doUpsert(connectorCode, identity, spec);
    }

    /**
     * Приводит набор задач identity в соответствие с декларацией коннектора: upsert всех
     * актуальных + удаление строк, чьи {@code task_name} больше не возвращаются {@code getTasks()}.
     * {@code REQUIRES_NEW} — по той же причине, что и {@link #upsert}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void syncIdentity(String connectorCode, String identity, Collection<TaskSpecification> specs) {
        if (specs.isEmpty()) {
            repository.deleteByIdentity(connectorCode, identity);
            return;
        }
        for (TaskSpecification spec : specs) {
            doUpsert(connectorCode, identity, spec);
        }
        repository.deleteStale(connectorCode, identity,
                specs.stream().map(TaskSpecification::name).toList());
    }

    /** {@code REQUIRES_NEW} — по той же причине, что и {@link #upsert}: вызов из AFTER_COMMIT listener'а. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteByIdentity(String connectorCode, String identity) {
        return repository.deleteByIdentity(connectorCode, identity);
    }

    /**
     * Завершает текущую итерацию: переводит в {@code PENDING}, очищает lease, выставляет
     * {@code next_run_at}. {@code lastError == null} означает успех.
     */
    @Transactional
    public void complete(UUID taskId, LocalDateTime nextRunAt, String lastError) {
        repository.complete(taskId, nextRunAt, trimError(lastError));
    }

    /** Финализирует успешно выполненный ONETIME: {@code status=COMPLETED}, без следующего запуска. */
    @Transactional
    public void markCompleted(UUID taskId, String lastError) {
        repository.markCompleted(taskId, trimError(lastError));
    }

    private ConnectorTask doUpsert(String connectorCode, String identity, TaskSpecification spec) {
        ConnectorTask row = repository.findByBusinessKey(connectorCode, identity, spec.name())
                .orElseGet(() -> ConnectorTask.builder()
                        .connectorCode(connectorCode)
                        .identity(identity)
                        .taskName(spec.name())
                        .status(ConnectorTaskStatus.PENDING)
                        .nextRunAt(LocalDateTime.now())
                        .build());

        if (row.getStatus() == ConnectorTaskStatus.COMPLETED) {
            row.setStatus(ConnectorTaskStatus.PENDING);
            row.setNextRunAt(LocalDateTime.now());
        }
        row.setTaskType(spec.taskType());
        row.setTaskConfig(spec.taskConfig());
        row.setTaskArgs(spec.taskArgs());
        row.setTimeoutSeconds(spec.timeoutSeconds());

        return repository.save(row);
    }

    private static String trimError(String lastError) {
        return (lastError == null || lastError.length() <= LAST_ERROR_LIMIT)
                ? lastError
                : lastError.substring(0, LAST_ERROR_LIMIT);
    }
}
