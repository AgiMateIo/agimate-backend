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

    Optional<AgentPreset> findByName(String name);

    List<AgentPreset> findAllByEnabledTrueOrderBySortOrderAscNameAsc();

    List<AgentPreset> findAllByOrderBySortOrderAscNameAsc();

    /** Whether any preset references a skill by name (skill_names is a text[]). */
    @Query(value = "SELECT COUNT(*) > 0 FROM agent_presets WHERE :name = ANY(skill_names)",
            nativeQuery = true)
    boolean existsBySkillNameReferenced(@Param("name") String name);
}
