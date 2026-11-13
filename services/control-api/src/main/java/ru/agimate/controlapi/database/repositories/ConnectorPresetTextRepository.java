package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.ConnectorPresetText;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorPresetTextRepository extends JpaRepository<ConnectorPresetText, UUID> {

    List<ConnectorPresetText> findAllByPresetIdInAndLanguage(Collection<UUID> presetIds,
                                                             ContentLanguage language);

    Optional<ConnectorPresetText> findByPresetIdAndLanguage(UUID presetId, ContentLanguage language);

    /** The seed owns a preset's translations wholesale: they are replaced, not merged. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ConnectorPresetText t WHERE t.presetId = :presetId")
    int deleteByPresetId(@Param("presetId") UUID presetId);
}
