package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmQuota;
import ru.agimate.controlapi.database.enums.UsageSubjectKind;
import ru.agimate.controlapi.database.enums.UsageWindow;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmQuotaRepository extends JpaRepository<LlmQuota, UUID> {

    List<LlmQuota> findAllByLlmProviderId(UUID llmProviderId);

    List<LlmQuota> findAllByLlmProviderIdIn(Collection<UUID> llmProviderIds);

    Optional<LlmQuota> findByIdAndLlmProviderId(UUID id, UUID llmProviderId);

    boolean existsByLlmProviderIdAndSubjectKindAndWindow(UUID llmProviderId,
                                                         UsageSubjectKind subjectKind,
                                                         UsageWindow window);
}
