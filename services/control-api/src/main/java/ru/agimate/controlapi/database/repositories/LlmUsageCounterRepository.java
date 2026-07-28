package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmUsageCounter;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmUsageCounterRepository extends JpaRepository<LlmUsageCounter, UUID> {

    Optional<LlmUsageCounter> findByLlmProviderIdAndSubjectKindAndSubjectIdAndWindowAndWindowStart(
            UUID llmProviderId, UsageSubjectKind subjectKind, UUID subjectId,
            UsageWindow window, LocalDate windowStart);

    /** Atomic increment of a window's counter: inserts the first row or adds to an existing one. */
    @Modifying
    @Query(value = """
            INSERT INTO llm_usage_counters
                (llm_provider_id, subject_kind, subject_id, win, window_start, tokens, requests)
            VALUES (:providerId, :subjectKind, :subjectId, :win, :windowStart, :tokens, 1)
            ON CONFLICT (llm_provider_id, subject_kind, subject_id, win, window_start)
            DO UPDATE SET tokens = llm_usage_counters.tokens + EXCLUDED.tokens,
                          requests = llm_usage_counters.requests + 1,
                          updated_at = CURRENT_TIMESTAMP
            """, nativeQuery = true)
    void increment(@Param("providerId") UUID providerId,
                   @Param("subjectKind") String subjectKind,
                   @Param("subjectId") UUID subjectId,
                   @Param("win") String win,
                   @Param("windowStart") LocalDate windowStart,
                   @Param("tokens") long tokens);
}
