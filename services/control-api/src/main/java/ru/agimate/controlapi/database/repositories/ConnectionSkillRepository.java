package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.controlapi.database.entities.ConnectionSkill;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConnectionSkillRepository extends JpaRepository<ConnectionSkill, UUID> {

    /** Includes soft-deleted rows: a returning key revives its row and keeps the copy's link. */
    Optional<ConnectionSkill> findByConnectionIdAndUri(UUID connectionId, String uri);

    /** Includes soft-deleted rows: the sync compares the declared catalogue with the stored one. */
    List<ConnectionSkill> findByConnectionIdOrderByNameAsc(UUID connectionId);

    List<ConnectionSkill> findByConnectionIdAndDeletedAtIsNullOrderByNameAsc(UUID connectionId);

    List<ConnectionSkill> findByConnectionIdInAndDeletedAtIsNullOrderByNameAsc(Collection<UUID> connectionIds);
}
