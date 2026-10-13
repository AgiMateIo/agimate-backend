package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.AgentPreset;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentPresetRepository extends JpaRepository<AgentPreset, UUID> {

    Optional<AgentPreset> findByNameAndLanguage(String name, ContentLanguage language);

    List<AgentPreset> findAllByLanguageAndEnabledTrueOrderBySortOrderAscNameAsc(ContentLanguage language);

    List<AgentPreset> findAllByLanguageOrderBySortOrderAscNameAsc(ContentLanguage language);

    /**
     * Whether a preset references the skill by name (skill_names is a text[]). A language-less skill
     * can be any preset's; a language version — only that language's presets'.
     */
    default boolean existsBySkillNameReferenced(String name, ContentLanguage language) {
        return language == null ? existsBySkillNameReferenced(name) : existsBySkillNameReferencedIn(name, language.name());
    }

    @Query(value = "SELECT COUNT(*) > 0 FROM agent_presets WHERE :name = ANY(skill_names)", nativeQuery = true)
    boolean existsBySkillNameReferenced(@Param("name") String name);

    @Query(value = "SELECT COUNT(*) > 0 FROM agent_presets WHERE :name = ANY(skill_names) AND language = :language",
            nativeQuery = true)
    boolean existsBySkillNameReferencedIn(@Param("name") String name, @Param("language") String language);
}
