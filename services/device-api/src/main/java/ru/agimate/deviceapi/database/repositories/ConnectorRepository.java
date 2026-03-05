package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Connector;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, String> {
}
