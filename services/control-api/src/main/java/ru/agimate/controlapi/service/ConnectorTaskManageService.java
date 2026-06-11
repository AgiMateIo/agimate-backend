package ru.agimate.controlapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.BadRequestStatusException;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.controlapi.connectors.core.tasks.TaskSchedule;
import ru.agimate.controlapi.controller.manage.dto.ConnectorTaskResponse;
import ru.agimate.controlapi.database.entities.ConnectorTask;
import ru.agimate.controlapi.database.enums.ConnectorTaskKind;
import ru.agimate.controlapi.database.enums.ConnectorTaskStatus;
import ru.agimate.controlapi.database.repositories.ConnectorTaskRepository;
import ru.agimate.controlapi.database.repositories.ConnectorTaskSpecs;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Пользовательское управление фоновыми задачами коннекторов (manage-API): список, pause/resume,
 * удаление. Мутации записи scheduler'а ({@code status}/{@code lease_until}) не трогают — пауза и
 * возобновление идут точечными UPDATE только своих полей.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConnectorTaskManageService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ConnectorTaskRepository connectorTaskRepository;

    public Page<ConnectorTaskResponse> getTasks(UUID userId, String connectorCode, ConnectorTaskKind kind,
                                                int page, int size) {
        Specification<ConnectorTask> spec = ConnectorTaskSpecs.ownedBy(userId);
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(ConnectorTaskSpecs.hasConnector(connectorCode));
        }
        if (kind != null) {
            spec = spec.and(ConnectorTaskSpecs.hasKind(kind));
        }
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by("nextRunAt").ascending());
        return connectorTaskRepository.findAll(spec, pageRequest).map(ConnectorTaskResponse::from);
    }

    @Transactional
    public void pause(UUID id, UUID userId) {
        ConnectorTask task = findOwnedTask(id, userId);
        requireNotCompleted(task);
        connectorTaskRepository.pause(task.getId(), userId, LocalDateTime.now());
        log.info("Paused connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getTaskName(), userId);
    }

    @Transactional
    public void resume(UUID id, UUID userId) {
        ConnectorTask task = findOwnedTask(id, userId);
        requireNotCompleted(task);
        if (task.getPausedAt() == null) {
            return;
        }
        connectorTaskRepository.resume(task.getId(), userId, nextRunAfterResume(task));
        log.info("Resumed connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getTaskName(), userId);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        ConnectorTask task = findOwnedTask(id, userId);
        if (task.getKind() == ConnectorTaskKind.SYSTEM) {
            // Reconcile-синк воссоздал бы строку на ближайшем событии — delete не приживётся.
            throw new BadRequestStatusException(
                    "Declarative connector task cannot be deleted: pause it or delete the integration");
        }
        connectorTaskRepository.delete(task);
        log.info("Deleted connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getTaskName(), userId);
    }

    /**
     * Пересчёт {@code next_run_at} при возобновлении: PERIODIC/CRON стартуют от «сейчас», а не
     * догоняют срок, пропущенный за время паузы; ONETIME сохраняет свой момент (просроченный
     * выполнится сразу — пользователь явно включил задачу).
     */
    private LocalDateTime nextRunAfterResume(ConnectorTask task) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> config = task.getTaskConfig() == null ? Map.of() : task.getTaskConfig();
        return switch (task.getTaskType()) {
            case ONETIME -> task.getNextRunAt();
            case PERIODIC -> now.plusSeconds(TaskSchedule.readLong(config, "intervalSeconds", 0L));
            case CRON -> TaskSchedule.nextCron(config, now);
        };
    }

    private ConnectorTask findOwnedTask(UUID id, UUID userId) {
        return connectorTaskRepository.findById(id)
                .filter(task -> task.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Task not found"));
    }

    private void requireNotCompleted(ConnectorTask task) {
        if (task.getStatus() == ConnectorTaskStatus.COMPLETED) {
            throw new BadRequestStatusException("Task is already completed");
        }
    }
}
