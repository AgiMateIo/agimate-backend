package ru.agimate.controlapi.connectors.internal.persistentmemory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.AgentConnection;
import ru.agimate.controlapi.database.entities.Connection;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;
import ru.agimate.controlapi.database.repositories.AgentConnectionRepository;
import ru.agimate.controlapi.database.repositories.ConnectionRepository;
import ru.agimate.controlapi.database.repositories.PersistentMemoryColdRepository;
import ru.agimate.controlapi.database.repositories.PersistentMemoryHotRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Хранилище persistent memory: cold (свёрнутый файл) и hot (журнал заметок).
 *
 * <p>Инварианты конкурентности:
 * <ul>
 *   <li>hot пишется только append'ом (INSERT) — конкурентные записи не конфликтуют;</li>
 *   <li>cold пишется через CAS по {@code version} — параллельная консолидация получит конфликт;</li>
 *   <li>запись cold и удаление заклеймленных заметок — в одной транзакции ({@link #updateMemory}).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersistentMemoryService {

    private final PersistentMemoryColdRepository coldRepository;
    private final PersistentMemoryHotRepository hotRepository;
    private final ConnectionRepository connectionRepository;
    private final AgentConnectionRepository agentConnectionRepository;

    /** scope-носитель экземпляра по его {@code connections.id} (connectionId тулы/таски). */
    public Optional<UUID> scopeIdForConnection(UUID connectionId) {
        return connectionRepository.findByIdNotDeleted(connectionId).map(Connection::getScopeId);
    }

    /** scope-носитель памяти агента: scope_id его активной memory-привязки (AGENT→agentId, TEAM→teamId). */
    public Optional<UUID> scopeIdForAgent(UUID agentId) {
        for (AgentConnection b : agentConnectionRepository.findActiveByAgentId(agentId)) {
            Connection c = connectionRepository.findByIdNotDeleted(b.getConnectionId()).orElse(null);
            if (c != null && PersistentMemoryConnectorService.CONNECTOR_CODE.equals(c.getConnectorCode())) {
                return Optional.ofNullable(c.getScopeId());
            }
        }
        return Optional.empty();
    }

    /** Все агенты, привязанные к данному memory-экземпляру (для роутинга фоновых триггеров по scope). */
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

    /** Добавляет заметку в hot (append). */
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
     * Записывает cold (CAS по version) и, если задан {@code consolidationId}, в той же транзакции
     * удаляет заметки этой партии. Конфликт версии → {@link ConnectorException} (агент перечитывает
     * и повторяет; заметки остаются заклеймлены под его {@code consolidationId}).
     *
     * @param expectedVersion ожидаемая версия cold; {@code null} допустимо только для первой записи
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

    /** Идёт ли у scope консолидация прямо сейчас (single-flight guard). */
    public boolean hasInFlightConsolidation(UUID scopeId, LocalDateTime leaseThreshold) {
        return hotRepository.countInFlight(scopeId, leaseThreshold) > 0;
    }

    /**
     * Клеймит несконсолидированные заметки scope под новую партию и возвращает их.
     * Пустой список — клеймить нечего.
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
