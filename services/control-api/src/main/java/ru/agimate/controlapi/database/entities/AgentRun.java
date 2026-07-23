package ru.agimate.controlapi.database.entities;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import ru.agimate.common.persistence.BaseEntity;
import ru.agimate.controlapi.database.enums.RunStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "agent_runs", uniqueConstraints =
        @UniqueConstraint(columnNames = {"trigger_log_id", "agent_id"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_log_id", nullable = false)
    private TriggerLog triggerLog;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_id", nullable = false)
    private Agent agent;

    @Column(name = "destination", nullable = false, columnDefinition = "TEXT")
    private String destination;

    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /**
     * Channel session this run writes to, or {@code null} for non-channel runs
     * (e.g. WEBHOOK/CENTRIFUGO delivery). Set by the backend at trigger routing.
     */
    @Column(name = "session_id")
    private UUID sessionId;

    /**
     * Run lifecycle — a projection of the run's {@code SaveMessage} stream (INBOUND → RUNNING,
     * ANSWER → DONE, ERROR → FAILED), observability only. Single-writer-per-session is enforced
     * by the partitioned {@code agent_exec} queue (a contract requirement on the transport),
     * not by this column.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private RunStatus status = RunStatus.ENQUEUED;

    /**
     * Последний признак жизни рана: продлевается его RPC (SaveMessage, GetLlmCredentials,
     * ExecuteToolAsync/GetToolResult). RUNNING без активности дольше порога добирает
     * фоновый сборщик ({@code RunActivityService}).
     */
    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    /**
     * Снапшот каналов маршрута ({@code Channels}: prompt/progress/answer), зафиксированный при
     * dispatch. Хранится сырой JSONB-мапой, чтобы entity-слой не зависел от service-типов;
     * типизацию даёт service-слой ({@code TriggerRouterService} пишет, {@code RunContextService}
     * читает). {@code null} — direct-ран без каналов.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channels", columnDefinition = "JSONB")
    private Map<String, Object> channels;

    /**
     * Снимок стартового промпта рана: список сообщений ровно как он ушёл в первый LLM-вызов
     * (system + history + триггер с ephemeral-префиксом). Пишет воркер один раз перед циклом
     * ({@code SavePrompt}), first-write-wins. Хранится opaque JSON-деревом — наблюдаемость, не
     * проекция; дальнейшие ходы рана идут в {@code agent_run_turns}. {@code null} — снимок ещё не
     * снят (ран не дошёл до цикла) либо ран до этой фичи. Пользовательский контент → до прода
     * подпадает под per-user DEK + retention, как {@code agent_run_turns}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "prompt", columnDefinition = "JSONB")
    private JsonNode prompt;
}
