package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.agimate.controlapi.database.entities.PersistentMemoryCold;

import java.util.Optional;
import java.util.UUID;

public interface PersistentMemoryColdRepository extends JpaRepository<PersistentMemoryCold, UUID> {

    Optional<PersistentMemoryCold> findByAgentId(UUID agentId);

    /**
     * Compare-and-swap записи cold: переписывает content и инкрементит version только если
     * текущая version совпадает с ожидаемой. {@code 0} строк → конфликт (память изменилась
     * параллельной консолидацией), вызывающий перечитывает и повторяет.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE PersistentMemoryCold c
            SET c.content = :content, c.version = c.version + 1
            WHERE c.agentId = :agentId AND c.version = :expectedVersion
            """)
    int casUpdate(@Param("agentId") UUID agentId,
                  @Param("content") String content,
                  @Param("expectedVersion") int expectedVersion);
}
