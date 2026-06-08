package ru.agimate.controlapi.connectors.tasks;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskStatus;
import ru.agimate.controlapi.database.enums.ConnectorTaskType;
import ru.agimate.controlapi.database.repositories.ConnectorTaskRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * API записи в {@code connector_tasks}. Лежит между listener'ами/admin'ом и БД.
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

    @Transactional(readOnly = true)
    public List<ConnectorTask> claimReady(Duration leaseDuration, int batchSize) {
        return repository.claimReady(LocalDateTime.now(), leaseDuration, batchSize);
    }

    @Transactional(readOnly = true)
    public Optional<ConnectorTask> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Создаёт или обновляет строку по бизнес‑ключу {@code (connectorCode, scope, taskCode)}.
     * Для новой строки выставляется {@code status=PENDING}, {@code next_run_at=now()} —
     * scheduler подхватит её на ближайшем тике.
     */
    @Transactional
    public ConnectorTask upsert(String connectorCode,
                                TaskScope scope,
                                String taskCode,
                                String identity,
                                ConnectorTaskType taskType,
                                Map<String, Object> config) {
        Optional<ConnectorTask> existing = repository.findByBusinessKey(
                connectorCode, scope.kind(), scope.id(), taskCode);

        ConnectorTask row = existing.orElseGet(() -> ConnectorTask.builder()
                .connectorCode(connectorCode)
                .scopeKind(scope.kind())
                .scopeId(scope.id())
                .taskCode(taskCode)
                .enabled(true)
                .status(ConnectorTaskStatus.PENDING)
                .nextRunAt(LocalDateTime.now())
                .build());

        row.setIdentity(identity);
        row.setTaskType(taskType);
        row.setConfig(config);

        return repository.save(row);
    }

    /**
     * Удобный upsert из {@link TaskDescriptor}: подбирает {@link ConnectorTaskType} и сериализует
     * параметры расписания в {@code config}. Используется обоими «писателями» — listener'ом
     * интеграций и bootstrap'ом internal Global задач.
     */
    @Transactional
    public ConnectorTask upsertFromDescriptor(String connectorCode,
                                              TaskScope scope,
                                              String identity,
                                              TaskDescriptor descriptor) {
        ConnectorTaskType taskType = switch (descriptor) {
            case TaskDescriptor.Periodic ignored -> ConnectorTaskType.PERIODIC;
            case TaskDescriptor.Cron ignored -> ConnectorTaskType.CRON;
        };
        Map<String, Object> config = new LinkedHashMap<>();
        switch (descriptor) {
            case TaskDescriptor.Periodic p -> config.put("intervalSeconds", p.interval().toSeconds());
            case TaskDescriptor.Cron c -> {
                config.put("cron", c.cronExpression());
                config.put("zone", c.zone().getId());
            }
        }
        return upsert(connectorCode, scope, descriptor.taskCode(), identity, taskType, config);
    }

    @Transactional
    public int deleteByScope(String connectorCode, TaskScope scope) {
        return repository.deleteByScope(connectorCode, scope.kind(), scope.id());
    }

    /**
     * Включает / отключает задачу без удаления — для админских правок в runtime.
     * При выключении задача доедет до конца текущей итерации (если RUNNING), но больше не будет
     * подхвачена.
     */
    @Transactional
    public void setEnabled(UUID taskId, boolean enabled) {
        repository.updateEnabled(taskId, enabled);
    }

    /**
     * Завершает текущую итерацию: переводит в {@code PENDING}, очищает lease, выставляет
     * {@code next_run_at}. {@code lastError == null} означает успех.
     */
    @Transactional
    public void complete(UUID taskId, LocalDateTime nextRunAt, String lastError) {
        String trimmed = (lastError == null || lastError.length() <= LAST_ERROR_LIMIT)
                ? lastError
                : lastError.substring(0, LAST_ERROR_LIMIT);
        repository.complete(taskId, nextRunAt, trimmed);
    }
}
