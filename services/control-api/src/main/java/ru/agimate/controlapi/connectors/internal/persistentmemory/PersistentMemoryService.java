package ru.agimate.controlapi.connectors.internal.persistentmemory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.PersistentMemoryColdRepository;
import ru.agimate.controlapi.database.repositories.PersistentMemoryHotRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Store of persistent memory: cold (the consolidated file) and hot (the note journal).
 *
 * <p>Concurrency invariants:
 * <ul>
 *   <li>hot is written by append (INSERT) only — concurrent writes never conflict;</li>
 *   <li>cold is written through a CAS on {@code version} — a concurrent consolidation gets a conflict;</li>
 *   <li>writing cold and deleting the claimed notes happen in one transaction ({@link #updateMemory}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersistentMemoryService {

    private final PersistentMemoryColdRepository coldRepository;
    private final PersistentMemoryHotRepository hotRepository;
    private final AgentConnectionRepository agentConnectionRepository;

    /** Every agent bound to the memory row (the background jobs walk their personal spaces). */
    public List<UUID> boundAgents(UUID connectionId) {
        return agentConnectionRepository.findActiveByConnectionId(connectionId).stream()
                .map(AgentConnection::getAgentId)
                .toList();
    }

    public Optional<PersistentMemoryCold> getCold(UUID scopeId) {
        return coldRepository.findByScopeId(scopeId);
    }

    public List<PersistentMemoryHot> getNotes(UUID scopeId) {
        return hotRepository.findByScopeIdOrderByCreatedAtAsc(scopeId);
    }

    /** Appends a note to hot. */
    @Transactional
    public PersistentMemoryHot addNote(UUID scopeId, UUID userId, UUID sessionId, String content) {
        return hotRepository.save(PersistentMemoryHot.builder()
                .scopeId(scopeId)
                .userId(userId)
                .sessionId(sessionId)
                .content(content)
                .build());
    }

    /**
     * Writes cold (a CAS on version) and, when {@code consolidationId} is given, deletes that batch's
     * notes in the same transaction. A version conflict → {@link ConnectorException} (the agent
     * re-reads and retries; the notes stay claimed under its {@code consolidationId}).
     *
     * @param expectedVersion the expected version of cold; {@code null} is acceptable only for the first write
     */
    @Transactional
    public void updateMemory(UUID scopeId, UUID userId, String content,
                             Integer expectedVersion, UUID consolidationId) {
        PersistentMemoryCold cold = coldRepository.findByScopeId(scopeId).orElse(null);
        if (cold == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw new ConnectorException("Version conflict: you passed version " + expectedVersion
                        + " but no consolidated memory exists yet (it may have been reset). Call get_memory "
                        + "to check, then call update_memory omitting version (or version 0) to create it.");
            }
            coldRepository.save(PersistentMemoryCold.builder()
                    .scopeId(scopeId)
                    .userId(userId)
                    .content(content)
                    .version(1)
                    .build());
        } else {
            if (expectedVersion == null) {
                throw new ConnectorException(
                        "version is required: read current memory via get_memory and pass its version");
            }
            int updated = coldRepository.casUpdate(scopeId, content, expectedVersion);
            if (updated == 0) {
                throw new ConnectorException("Version conflict: your version " + expectedVersion
                        + " is stale — the memory was updated since your last get_memory. Call get_memory to "
                        + "get the current version and content, re-apply your changes on top, then call "
                        + "update_memory again with the new version.");
            }
        }
        if (consolidationId != null) {
            hotRepository.deleteByConsolidationId(consolidationId);
        }
    }

    /** Whether a consolidation is running for the scope right now (the single-flight guard). */
    public boolean hasInFlightConsolidation(UUID scopeId, LocalDateTime leaseThreshold) {
        return hotRepository.countInFlight(scopeId, leaseThreshold) > 0;
    }

    /**
     * Claims the scope's unconsolidated notes under a new batch and returns them. An empty list means
     * there is nothing to claim.
     */
    @Transactional
    public List<PersistentMemoryHot> claimNotesForConsolidation(UUID scopeId, UUID consolidationId,
                                                                LocalDateTime now, LocalDateTime leaseThreshold) {
        int claimed = hotRepository.claim(scopeId, consolidationId, now, leaseThreshold);
        if (claimed == 0) {
            return List.of();
        }
        return hotRepository.findByConsolidationIdOrderByCreatedAtAsc(consolidationId);
    }
}
