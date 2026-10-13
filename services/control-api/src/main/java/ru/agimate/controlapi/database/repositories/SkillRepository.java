package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Skill;
import ru.agimate.controlapi.database.enums.ContentLanguage;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SkillRepository extends JpaRepository<Skill, UUID>, JpaSpecificationExecutor<Skill> {

    @Query("SELECT s FROM Skill s WHERE s.id = :id AND s.deletedAt IS NULL")
    Optional<Skill> findByIdNotDeleted(@Param("id") UUID id);

    /** The key {@code (user_id, name, language)} is taken; a {@code null} language matches only a language-less row. */
    default boolean existsByKeyNotDeleted(UUID userId, String name, ContentLanguage language) {
        // Two queries rather than one with a nullable parameter: «:language IS NULL» on a null enum binding is
        // left to the JDBC driver's type inference.
        return language == null
                ? existsLanguageLessNotDeleted(userId, name)
                : existsInLanguageNotDeleted(userId, name, language);
    }

    @Query("SELECT COUNT(s) > 0 FROM Skill s WHERE s.userId = :userId AND s.name = :name AND s.language IS NULL"
            + " AND s.deletedAt IS NULL")
    boolean existsLanguageLessNotDeleted(@Param("userId") UUID userId, @Param("name") String name);

    @Query("SELECT COUNT(s) > 0 FROM Skill s WHERE s.userId = :userId AND s.name = :name AND s.language = :language"
            + " AND s.deletedAt IS NULL")
    boolean existsInLanguageNotDeleted(@Param("userId") UUID userId, @Param("name") String name,
                                       @Param("language") ContentLanguage language);

    @Query("SELECT s FROM Skill s WHERE s.id IN :ids AND s.deletedAt IS NULL")
    List<Skill> findByIdInNotDeleted(@Param("ids") Collection<UUID> ids);

    /** Exactly this language version — the seeder's key; no fallback to a language-less row. */
    @Query("SELECT s FROM Skill s WHERE s.userId = :userId AND s.name = :name AND s.language = :language"
            + " AND s.deletedAt IS NULL")
    Optional<Skill> findByUserIdAndNameAndLanguageNotDeleted(@Param("userId") UUID userId, @Param("name") String name,
                                                             @Param("language") ContentLanguage language);

    @Query("SELECT s FROM Skill s WHERE s.userId = :userId AND s.name IN :names AND s.deletedAt IS NULL"
            + " AND (s.language = :language OR s.language IS NULL)")
    List<Skill> findCandidatesNotDeleted(@Param("userId") UUID userId, @Param("names") Collection<String> names,
                                         @Param("language") ContentLanguage language);

    /**
     * Skills by name as seen from {@code language}: that language's version, else the language-less
     * one. The only resolution of a skill name — a preset's skills, a skill the platform binds itself.
     */
    default Map<String, Skill> findByNamesInLanguage(UUID userId, Collection<String> names, ContentLanguage language) {
        if (names.isEmpty()) {
            return Map.of();
        }
        Map<String, Skill> byName = new HashMap<>();
        for (Skill skill : findCandidatesNotDeleted(userId, names, language)) {
            byName.merge(skill.getName(), skill, (current, other) -> current.getLanguage() != null ? current : other);
        }
        return byName;
    }

    default Optional<Skill> findByNameInLanguage(UUID userId, String name, ContentLanguage language) {
        return Optional.ofNullable(findByNamesInLanguage(userId, List.of(name), language).get(name));
    }

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Skill s SET s.deletedAt = :now WHERE s.id = :id")
    void softDelete(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
