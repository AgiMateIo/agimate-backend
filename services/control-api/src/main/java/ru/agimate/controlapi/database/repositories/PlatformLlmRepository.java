package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.PlatformLlm;
import ru.agimate.controlapi.database.enums.LlmPurpose;

import java.util.List;
import java.util.UUID;

@Repository
public interface PlatformLlmRepository extends JpaRepository<PlatformLlm, UUID> {

    List<PlatformLlm> findAllByOrderByPurposeAscPriorityAsc();

    List<PlatformLlm> findAllByPurposeOrderByPriorityAsc(LlmPurpose purpose);

    boolean existsByLlmProviderId(UUID llmProviderId);
}
