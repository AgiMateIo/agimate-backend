package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.StoredFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    /** Сумма байтов, загруженных пользователем с {@code since} (окно суточной квоты). */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.userId = :userId and f.createdAt >= :since")
    long sumBytesSince(@Param("userId") UUID userId, @Param("since") LocalDateTime since);

    /**
     * Батч на удаление под блокировкой: просроченные READY + брошенные UPLOADING (старше часа).
     * {@code FOR UPDATE SKIP LOCKED} — несколько инстансов чистки не дерутся за одни строки;
     * вызывать только внутри транзакции.
     */
    @Query(value = """
            SELECT * FROM files
            WHERE (status = 'READY' AND expires_at < now())
               OR (status = 'UPLOADING' AND created_at < now() - interval '1 hour')
            ORDER BY expires_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<StoredFile> claimPurgeBatch(@Param("limit") int limit);
}
