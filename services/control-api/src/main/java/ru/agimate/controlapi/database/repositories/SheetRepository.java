package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Sheet;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SheetRepository extends JpaRepository<Sheet, UUID> {

    Optional<Sheet> findByScopeIdAndName(UUID scopeId, String name);

    List<Sheet> findByScopeIdOrderByNameAsc(UUID scopeId);

    boolean existsByScopeIdAndName(UUID scopeId, String name);
}
