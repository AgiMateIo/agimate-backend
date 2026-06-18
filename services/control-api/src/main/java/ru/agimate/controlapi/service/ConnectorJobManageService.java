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
import ru.agimate.controlapi.connectors.core.jobs.JobSchedule;
import ru.agimate.controlapi.controller.manage.dto.ConnectorJobResponse;
import ru.agimate.controlapi.database.entities.ConnectorJob;
import ru.agimate.controlapi.database.enums.ConnectorJobKind;
import ru.agimate.controlapi.database.enums.ConnectorJobStatus;
import ru.agimate.controlapi.database.repositories.ConnectorJobRepository;
import ru.agimate.controlapi.database.repositories.ConnectorJobSpecs;

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
public class ConnectorJobManageService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ConnectorJobRepository connectorJobRepository;

    public Page<ConnectorJobResponse> getJobs(UUID userId, String connectorCode, ConnectorJobKind kind,
                                                int page, int size) {
        Specification<ConnectorJob> spec = ConnectorJobSpecs.ownedBy(userId);
        if (connectorCode != null && !connectorCode.isBlank()) {
            spec = spec.and(ConnectorJobSpecs.hasConnector(connectorCode));
        }
        if (kind != null) {
            spec = spec.and(ConnectorJobSpecs.hasKind(kind));
        }
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE),
                Sort.by("nextRunAt").ascending());
        return connectorJobRepository.findAll(spec, pageRequest).map(ConnectorJobResponse::from);
    }

    @Transactional
    public void pause(UUID id, UUID userId) {
        ConnectorJob task = findOwnedTask(id, userId);
        requireNotCompleted(task);
        connectorJobRepository.pause(task.getId(), userId, LocalDateTime.now());
        log.info("Paused connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getName(), userId);
    }

    @Transactional
    public void resume(UUID id, UUID userId) {
        ConnectorJob task = findOwnedTask(id, userId);
        requireNotCompleted(task);
        if (task.getPausedAt() == null) {
            return;
        }
        connectorJobRepository.resume(task.getId(), userId, nextRunAfterResume(task));
        log.info("Resumed connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getName(), userId);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        ConnectorJob task = findOwnedTask(id, userId);
        if (task.getKind() == ConnectorJobKind.SYSTEM) {
            // Reconcile-синк воссоздал бы строку на ближайшем событии — delete не приживётся.
            throw new BadRequestStatusException(
                    "Declarative connector task cannot be deleted: pause it or delete the integration");
        }
        connectorJobRepository.delete(task);
        log.info("Deleted connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getName(), userId);
    }

    /**
     * Пересчёт {@code next_run_at} при возобновлении: PERIODIC/CRON стартуют от «сейчас», а не
     * догоняют срок, пропущенный за время паузы; ONETIME сохраняет свой момент (просроченный
     * выполнится сразу — пользователь явно включил задачу).
     */
    private LocalDateTime nextRunAfterResume(ConnectorJob task) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> config = task.getConfig() == null ? Map.of() : task.getConfig();
        return switch (task.getType()) {
            case ONETIME -> task.getNextRunAt();
            case PERIODIC -> now.plusSeconds(JobSchedule.readLong(config, "intervalSeconds", 0L));
            case CRON -> JobSchedule.nextCron(config, now);
        };
    }

    private ConnectorJob findOwnedTask(UUID id, UUID userId) {
        return connectorJobRepository.findById(id)
                .filter(task -> task.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundStatusException("Job not found"));
    }

    private void requireNotCompleted(ConnectorJob task) {
        if (task.getStatus() == ConnectorJobStatus.COMPLETED) {
            throw new BadRequestStatusException("Job is already completed");
        }
    }
}
