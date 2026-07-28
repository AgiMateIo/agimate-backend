package ru.agimate.controlapi.database.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.util.UUID;

/**
 * Journal of LLM calls (per call): the source of truth for usage accounting, audit and debugging.
 * Report idempotency is UNIQUE {@code call_id} (the LLM call's DBOS workflow id); the insert uses a
 * native {@code ON CONFLICT DO NOTHING}, and the counters are incremented only for a new row.
 */
@Entity
@Table(name = "llm_usage_log", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_usage_log_call_id", columnNames = {"call_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmUsageLog extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "call_id", nullable = false, columnDefinition = "TEXT")
    private String callId;

    /** agent_runs.id (the LLM call's parent workflow); null when the worker does not know it. */
    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "llm_provider_id", nullable = false)
    private UUID llmProviderId;

    @Column(name = "model", nullable = false, columnDefinition = "TEXT")
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private Integer inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private Integer outputTokens;

    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Integer cacheWriteTokens;
}
