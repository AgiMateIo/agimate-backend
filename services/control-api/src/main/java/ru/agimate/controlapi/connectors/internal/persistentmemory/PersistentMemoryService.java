package ru.agimate.controlapi.connectors.internal.persistentmemory;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.connectors.core.ConnectorException;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;
import ru.agimate.controlapi.database.entities.PersistentMemoryHot;
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

    public Optional<PersistentMemoryCold> getCold(UUID agentId) {
        return coldRepository.findByAgentId(agentId);
    }

    public List<PersistentMemoryHot> getNotes(UUID agentId) {
        return hotRepository.findByAgentIdOrderByCreatedAtAsc(agentId);
    }

    /** Добавляет заметку в hot (append). */
    @Transactional
    public PersistentMemoryHot addNote(UUID agentId, UUID userId, UUID sessionId, String content) {
        return hotRepository.save(PersistentMemoryHot.builder()
                .agentId(agentId)
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
    public void updateMemory(UUID agentId, UUID userId, String content,
                             Integer expectedVersion, UUID consolidationId) {
        PersistentMemoryCold cold = coldRepository.findByAgentId(agentId).orElse(null);
        if (cold == null) {
            if (expectedVersion != null && expectedVersion != 0) {
                throw new ConnectorException("Memory changed: re-read it via get_memory and retry");
            }
            coldRepository.save(PersistentMemoryCold.builder()
                    .agentId(agentId)
                    .userId(userId)
                    .content(content)
                    .version(1)
                    .build());
        } else {
            if (expectedVersion == null) {
                throw new ConnectorException(
                        "version is required: read current memory via get_memory and pass its version");
            }
            int updated = coldRepository.casUpdate(agentId, content, expectedVersion);
            if (updated == 0) {
                throw new ConnectorException("Memory changed: re-read it via get_memory and retry");
            }
        }
        if (consolidationId != null) {
            hotRepository.deleteByConsolidationId(consolidationId);
        }
    }

    /** Идёт ли у агента консолидация прямо сейчас (single-flight guard). */
    public boolean hasInFlightConsolidation(UUID agentId, LocalDateTime leaseThreshold) {
        return hotRepository.countInFlight(agentId, leaseThreshold) > 0;
    }

    /**
     * Клеймит несконсолидированные заметки агента под новую партию и возвращает их.
     * Пустой список — клеймить нечего.
     */
    @Transactional
    public List<PersistentMemoryHot> claimNotesForConsolidation(UUID agentId, UUID consolidationId,
                                                                LocalDateTime now, LocalDateTime leaseThreshold) {
        int claimed = hotRepository.claim(agentId, consolidationId, now, leaseThreshold);
        if (claimed == 0) {
            return List.of();
        }
        return hotRepository.findByConsolidationIdOrderByCreatedAtAsc(consolidationId);
    }
}
