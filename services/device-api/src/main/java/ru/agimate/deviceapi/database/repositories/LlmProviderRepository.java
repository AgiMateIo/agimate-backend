package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.LlmProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProvider, UUID> {

    List<LlmProvider> findAllByUserPubIdOrderByCreatedAtDesc(UUID userPubId);

    Optional<LlmProvider> findByIdAndUserPubId(UUID id, UUID userPubId);

    List<LlmProvider> findAllByIdIn(List<UUID> ids);

    boolean existsByUserPubIdAndName(UUID userPubId, String name);
}
