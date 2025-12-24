package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.Connector;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, Long> {

    Optional<Connector> findByPubId(UUID pubId);

    Optional<Connector> findByCode(String code);

    @Query("SELECT c FROM Connector c WHERE c.enabled = true ORDER BY c.name")
    List<Connector> findAllEnabled();

    boolean existsByCode(String code);
}
