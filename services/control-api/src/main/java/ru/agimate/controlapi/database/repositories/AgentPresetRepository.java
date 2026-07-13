package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentPreset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentPresetRepository extends JpaRepository<AgentPreset, UUID> {

    Optional<AgentPreset> findByCode(String code);

    List<AgentPreset> findAllByEnabledTrueOrderBySortOrderAscNameAsc();

    List<AgentPreset> findAllByOrderBySortOrderAscNameAsc();

    /** Есть ли пресет, ссылающийся на скилл по имени (skill_names — text[]). */
    @Query(value = "SELECT COUNT(*) > 0 FROM agent_presets WHERE :name = ANY(skill_names)",
            nativeQuery = true)
    boolean existsBySkillNameReferenced(@Param("name") String name);
}
