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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A user's management of connectors' background jobs (the manage API): listing, pause/resume, deletion.
 * The mutations leave the scheduler's fields ({@code status}/{@code lease_until}) alone — pause and
 * resume go through targeted UPDATEs of their own fields only.
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

    /** Every job of a particular connector instance (owned). Instances, not declarations. */
    public List<ConnectorJobResponse> getConnectionJobs(UUID userId, UUID connectionId) {
        Specification<ConnectorJob> spec = ConnectorJobSpecs.ownedBy(userId)
                .and(ConnectorJobSpecs.hasConnection(connectionId.toString()));
        return connectorJobRepository.findAll(spec, Sort.by("nextRunAt").ascending())
                .stream()
                .map(ConnectorJobResponse::from)
                .toList();
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

    /**
     * Runs a job immediately: shifts {@code next_run_at} to «now», and the scheduler picks it up on the
     * next tick (≤1s). From status {@code PENDING} only and not while paused — the schedule's cadence is
     * preserved (the next run is recomputed the usual way after execution).
     */
    @Transactional
    public void runNow(UUID id, UUID userId) {
        ConnectorJob task = findOwnedTask(id, userId);
        requireNotCompleted(task);
        if (task.getPausedAt() != null) {
            throw new BadRequestStatusException("Job is paused: resume it first");
        }
        if (task.getStatus() == ConnectorJobStatus.RUNNING) {
            throw new BadRequestStatusException("Job is already running");
        }
        int updated = connectorJobRepository.runNow(task.getId(), userId, LocalDateTime.now());
        if (updated == 0) {
            // A race: the scheduler claimed the row between the load and the UPDATE.
            throw new BadRequestStatusException("Job is already running");
        }
        log.info("Run-now connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getName(), userId);
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        ConnectorJob task = findOwnedTask(id, userId);
        if (task.getKind() == ConnectorJobKind.SYSTEM) {
            // The reconcile sync would recreate the row on the next event — a delete would not stick.
            throw new BadRequestStatusException(
                    "Declarative connector task cannot be deleted: pause it or delete the integration");
        }
        connectorJobRepository.delete(task);
        log.info("Deleted connector task id={} ({}/{}) user={}",
                id, task.getConnectorCode(), task.getName(), userId);
    }

    /**
     * Recomputation of {@code next_run_at} on resume: PERIODIC/CRON start from «now» rather than catching
     * up on the deadline missed while paused; ONETIME keeps its moment (an overdue one runs at once — the
     * user explicitly re-enabled the job).
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
