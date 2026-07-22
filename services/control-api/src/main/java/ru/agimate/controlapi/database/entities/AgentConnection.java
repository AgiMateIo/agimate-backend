package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Привязка «connection доступна агенту» — M:N между {@code agents} и {@code connections}.
 * Это <b>гейт доступности</b>: нет активной строки → коннектор агенту недоступен (даже если
 * {@code connections}-запись существует). У внутренних коннекторов connection — строка-режим, одна
 * на пользователя: все его агенты с этим коннектором ссылаются на неё; владелец данных резолвится
 * кодом коннектора из {@code ConnectorEnv}. Привязки внутренних управляет скилл-синк
 * ({@code AgentSkillPolicyService}) и канальные сервисы; внешние (telegram/mcp/app) — явные.
 *
 * <p>Тулы по умолчанию разрешены при наличии binding; {@link AgentConnectionPolicy} лишь уточняет
 * (DENY конкретных, allow-list через wildcard, {@code params_filter}).
 *
 * <p>Уникальность среди активных: {@code (agent_id, connection_id) WHERE deleted_at IS NULL} —
 * partial unique индекс {@code uq_agent_connections_active} (JPA {@code @UniqueConstraint} partial
 * не выражает).
 */
@Entity
@Table(name = "agent_connections")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentConnection extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isActive() {
        return !isDeleted();
    }
}
