package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.LlmModelDefaults;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LlmModelDefaultsRepository extends JpaRepository<LlmModelDefaults, UUID> {

    List<LlmModelDefaults> findByModelIn(Collection<String> models);
}
