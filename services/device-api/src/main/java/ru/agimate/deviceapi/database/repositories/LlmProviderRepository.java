package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.LlmProvider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderRepository extends JpaRepository<LlmProvider, Long> {

    List<LlmProvider> findAllByUserPubIdOrderByCreatedAtDesc(UUID userPubId);

    Optional<LlmProvider> findByPubIdAndUserPubId(UUID pubId, UUID userPubId);

    Optional<LlmProvider> findByPubId(UUID pubId);

    List<LlmProvider> findAllByPubIdIn(List<UUID> pubIds);

    boolean existsByUserPubIdAndName(UUID userPubId, String name);
}
