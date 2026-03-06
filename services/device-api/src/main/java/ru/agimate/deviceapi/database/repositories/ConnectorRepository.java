package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, String> {

    boolean existsByCodeAndType(String code, ConnectorType type);
}
