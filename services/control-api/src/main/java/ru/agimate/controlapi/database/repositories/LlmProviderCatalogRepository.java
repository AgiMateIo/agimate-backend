package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmProviderCatalogEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LlmProviderCatalogRepository extends JpaRepository<LlmProviderCatalogEntry, UUID> {

    Optional<LlmProviderCatalogEntry> findByCode(String code);

    List<LlmProviderCatalogEntry> findByEnabledTrueOrderBySortOrderAscNameAsc();
}
