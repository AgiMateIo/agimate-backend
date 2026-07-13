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

import java.util.UUID;

/**
 * Квота расхода LLM-токенов на провайдере: платформенном (free-tier, USER — «каждому
 * пользователю N за окно») или BYOK (TOTAL — потолок кошелька, AGENT — лимит каждому агенту).
 * Метрика согласована со счётчиками: input + output + cache_write.
 * Одна активная квота на (провайдер, субъект, окно) — UNIQUE.
 */
@Entity
@Table(name = "llm_quotas", uniqueConstraints = {
        @UniqueConstraint(name = "uq_llm_quotas_key",
                columnNames = {"llm_provider_id", "subject_kind", "win"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmQuota extends BaseEntity {

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

    @Enumerated(EnumType.STRING)
    @Column(name = "win", nullable = false, columnDefinition = "TEXT")
    private UsageWindow window;

    @Column(name = "limit_tokens", nullable = false)
    private Long limitTokens;
}
