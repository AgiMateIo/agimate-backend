package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProvider, UUID> {

    List<LlmProvider> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<LlmProvider> findByIdAndUserId(UUID id, UUID userId);

    List<LlmProvider> findAllByIdIn(List<UUID> ids);

    boolean existsByUserIdAndName(UUID userId, String name);
}
