package ru.agimate.controlapi.database.repositories;

import ru.agimate.controlapi.database.entities.ConnectorTask;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Кастомные методы {@link ConnectorTaskRepository}, требующие native SQL — Spring Data JPA
 * не умеет {@code UPDATE … FROM (… FOR UPDATE SKIP LOCKED) RETURNING *} через {@code @Query}.
 */
public interface ConnectorTaskRepositoryCustom {

    /**
     * Атомарно подхватывает до {@code batchSize} готовых к запуску строк:
     * <ul>
     *   <li>{@code status=PENDING AND next_run_at <= now} — нормальный pickup;</li>
     *   <li>либо {@code status=RUNNING AND lease_until <= now} — crash‑recovery зависшей строки.</li>
     * </ul>
     *
     * <p>Под капотом {@code SELECT … FOR UPDATE SKIP LOCKED} обеспечивает корректное разделение
     * работы между несколькими нодами без блокировок. Возвращённые строки сразу переводятся в
     * {@code status=RUNNING} с lease до {@code now + timeout_seconds} (per-row) — отдельный
     * коммит на стороне caller'а не нужен.
     */
    List<ConnectorTask> claimReady(LocalDateTime now, int batchSize);
}
