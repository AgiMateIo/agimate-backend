package ru.agimate.controlapi.service.trigger;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.repositories.TriggerLogAgentRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Наблюдаемость жизни ранов. Live-ран постоянно ходит в control-api (SaveMessage,
 * ExecuteToolAsync/GetToolResult, GetRunContext) — каждый такой RPC продлевает
 * {@code last_activity_at}; ран, замолчавший дольше {@link #STALE_AFTER} (воркер умер без
 * SaveMessage(ERROR)), добирает сборщик. Никого не блокирует — single-writer держит
 * партиционированная очередь, статус — только проекция для истории/мониторинга.
 *
 * <p>Порог обязан превышать самый длинный легальный тихий участок рана — один LLM-вызов
 * со всеми его ретраями (воркер: 4 попытки с backoff).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunActivityService {

    static final Duration STALE_AFTER = Duration.ofMinutes(15);
    static final String STALE_ERROR = "run went silent (no worker activity); swept as stale";

    private final TriggerLogAgentRepository triggerLogAgentRepository;

    /** Признак жизни рана — best-effort: сбой метки не должен валить сам RPC. */
    public void touch(UUID runId) {
        try {
            triggerLogAgentRepository.touchActivity(runId, LocalDateTime.now());
        } catch (Exception e) {
            log.warn("touchActivity failed for run {}: {}", runId, e.getMessage());
        }
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void sweepStaleRunning() {
        int swept = triggerLogAgentRepository.failStaleRunning(
                LocalDateTime.now().minus(STALE_AFTER), STALE_ERROR);
        if (swept > 0) {
            log.warn("swept {} stale RUNNING run(s) older than {}", swept, STALE_AFTER);
        }
    }
}
