package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate of LLM token usage by (provider, subject, UTC calendar window) — the fast lookup for
 * quota enforcement and «how much is left». Written by an atomic upsert-increment inside the report
 * transaction ({@code ON CONFLICT ... DO UPDATE SET tokens = tokens + EXCLUDED.tokens}). For
 * {@code subject_kind = TOTAL} the subject is {@link #TOTAL_SUBJECT_ID}.
 */
@Entity
@Table(name = "llm_usage_counters", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_usage_counters_key",
                columnNames = {"llm_provider_id", "subject_kind", "subject_id", "win", "window_start"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageCounter extends BaseEntity {

    /** Synthetic subject_id of TOTAL rows (the provider's aggregate). */
    public static final UUID TOTAL_SUBJECT_ID = new UUID(0L, 0L);

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "llm_provider_id", nullable = false)
    private UUID llmProviderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_kind", nullable = false, columnDefinition = "TEXT")
    private UsageSubjectKind subjectKind;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "win", nullable = false, columnDefinition = "TEXT")
    private UsageWindow window;

    @Column(name = "window_start", nullable = false)
    private LocalDate windowStart;

    @Column(name = "tokens", nullable = false)
    private Long tokens;

    @Column(name = "requests", nullable = false)
    private Integer requests;
}
