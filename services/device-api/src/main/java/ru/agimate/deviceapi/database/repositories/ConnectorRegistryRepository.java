package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.ConnectorRegistry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorRegistryRepository extends JpaRepository<ConnectorRegistry, Long> {
    Optional<ConnectorRegistry> findByCode(String code);
    Optional<ConnectorRegistry> findByPubId(UUID pubId);
    List<ConnectorRegistry> findByUserPubId(UUID userPubId);
    boolean existsByCode(String code);
}
