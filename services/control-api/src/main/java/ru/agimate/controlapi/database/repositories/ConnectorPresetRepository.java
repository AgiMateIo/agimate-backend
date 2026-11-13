package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.entities.ConnectorPreset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorPresetRepository extends JpaRepository<ConnectorPreset, UUID> {

    Optional<ConnectorPreset> findByCode(String code);

    Optional<ConnectorPreset> findByCodeAndSourcePresentTrueAndEnabledTrue(String code);

    /**
     * The catalogue query. The search runs over both languages: {@code code}, the English columns and
     * every text row of the preset. The service escapes {@code %}, {@code _} and {@code \} before
     * binding; the empty search is {@code ''}, so {@code LIKE '%%'} matches everything.
     */
    @Query(value = """
            SELECT p.*
            FROM connector_presets p
            WHERE p.source_present = TRUE
              AND p.enabled = TRUE
              AND (:connectorCode IS NULL OR p.connector_code = :connectorCode)
              AND (:category IS NULL OR p.category = :category)
              AND (:jurisdiction IS NULL OR p.jurisdiction = :jurisdiction)
              AND (LOWER(p.code) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\\'
                   OR LOWER(p.name) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\\'
                   OR LOWER(p.description) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\\'
                   OR EXISTS (
                       SELECT 1 FROM connector_preset_texts x
                       WHERE x.preset_id = p.id
                         AND (LOWER(x.name) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\\'
                              OR LOWER(x.description) LIKE LOWER('%' || COALESCE(CAST(:search AS text), '') || '%') ESCAPE '\\')))
            """, nativeQuery = true)
    List<ConnectorPreset> search(@Param("search") String search,
                                 @Param("connectorCode") String connectorCode,
                                 @Param("category") String category,
                                 @Param("jurisdiction") String jurisdiction);

    /**
     * Hides every row before an upsert; a row whose file disappeared stays hidden. {@code updated_at}
     * is written explicitly because a bulk update bypasses {@code @UpdateTimestamp}.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ConnectorPreset p SET p.sourcePresent = false, p.updatedAt = :now "
            + "WHERE p.sourcePresent = true")
    int hideAll(@Param("now") LocalDateTime now);
}
