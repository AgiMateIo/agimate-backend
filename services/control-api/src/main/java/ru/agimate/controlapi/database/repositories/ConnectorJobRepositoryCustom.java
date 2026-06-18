package ru.agimate.controlapi.database.repositories;

import ru.agimate.controlapi.database.entities.ConnectorJob;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Кастомные методы {@link ConnectorJobRepository}, требующие native SQL — Spring Data JPA
 * не умеет {@code UPDATE … FROM (… FOR UPDATE SKIP LOCKED) RETURNING *} через {@code @Query}.
 */
public interface ConnectorJobRepositoryCustom {

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
    List<ConnectorJob> claimReady(LocalDateTime now, int batchSize);
}
