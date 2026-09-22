package ru.agimate.controlapi.service.session.compaction;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.AgentRunTurn;
import ru.agimate.controlapi.database.enums.AgentTurnRole;
import ru.agimate.controlapi.database.repositories.AgentRunTurnRepository;
import ru.agimate.controlapi.database.repositories.AgentSessionRepository;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The write half of a compaction, apart from {@link SessionCompactionService} so the model call
 * before it holds no transaction.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionCompactionWriter {

    private final AgentRunTurnRepository turnRepository;
    private final AgentSessionRepository sessionRepository;

    /**
     * The summary and the title in one transaction. A summary already standing on the anchor means a
     * twin job got there first: the title then stays the one written with that summary.
     *
     * @param summary {@code null} — a title-only pass
     * @param title   {@code null} — the model gave none; the summary still counts
     */
    @Transactional
    public void write(UUID sessionId, UUID agentId, UUID anchorRunId, String summary, String model, String title) {
        if (summary != null) {
            int inserted = turnRepository.insertIgnoreConflict(anchorRunId, sessionId, agentId,
                    AgentRunTurn.SUMMARY_TURN_INDEX, AgentTurnRole.SYSTEM.name(), summary,
                    null, null, null, null, model, null);
            if (inserted == 0) {
                log.info("session {} already has a summary on run {}, leaving it", sessionId, anchorRunId);
                return;
            }
        }
        if (title != null) {
            sessionRepository.writeGeneratedTitle(sessionId, title, LocalDateTime.now());
        }
    }
}
