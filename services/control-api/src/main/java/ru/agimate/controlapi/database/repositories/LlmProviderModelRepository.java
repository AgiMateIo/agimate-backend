package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmProviderModel;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderModelRepository extends JpaRepository<LlmProviderModel, UUID> {

    List<LlmProviderModel> findAllByLlmProviderIdOrderByModel(UUID providerId);

    Optional<LlmProviderModel> findByLlmProviderIdAndModel(UUID providerId, String model);
}
