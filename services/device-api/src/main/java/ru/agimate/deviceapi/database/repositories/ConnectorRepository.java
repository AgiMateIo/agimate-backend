package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Connector;
import ru.agimate.deviceapi.database.enums.ConnectorType;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, String> {

    boolean existsByCodeAndType(String code, ConnectorType type);

    @Query("""
            SELECT c FROM Connector c
            WHERE (:type IS NULL OR c.type = :type)
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(c.description) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<Connector> search(
            @Param("type") ConnectorType type,
            @Param("search") String search,
            Pageable pageable
    );
}
