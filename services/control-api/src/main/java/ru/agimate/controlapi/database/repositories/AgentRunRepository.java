package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentRun;

import java.time.LocalDateTime;
import java.util.UUID;

public interface AgentRunRepository extends JpaRepository<AgentRun, UUID> {

    // REQUIRES_NEW: вызовы идут и с голых gRPC-потоков (@Modifying без TX Hibernate отклоняет),
    // и из readOnly-транзакций фасадов (AgentContextGrpcService) — своя короткая пишущая TX
    // корректна из любого контекста.
    /** Признак жизни рана: любой его RPC продлевает метку активности (только пока RUNNING). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("""
            UPDATE AgentRun t
            SET t.lastActivityAt = :now
            WHERE t.id = :runId
              AND t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
            """)
    int touchActivity(@Param("runId") UUID runId, @Param("now") LocalDateTime now);

    /**
     * Сборщик залипших ранов: RUNNING без признаков жизни дольше порога → FAILED
     * (воркер умер молча — без SaveMessage(ERROR)). Наблюдаемость, никого не блокирует.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE AgentRun t
            SET t.status = ru.agimate.controlapi.database.enums.RunStatus.FAILED,
                t.error = :error
            WHERE t.status = ru.agimate.controlapi.database.enums.RunStatus.RUNNING
              AND t.lastActivityAt < :cutoff
            """)
    int failStaleRunning(@Param("cutoff") LocalDateTime cutoff, @Param("error") String error);
}
