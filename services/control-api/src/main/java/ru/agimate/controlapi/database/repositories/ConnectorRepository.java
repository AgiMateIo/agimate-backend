package ru.agimate.controlapi.database.repositories;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Connector;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, String> {

    @Query("""
            SELECT c FROM Connector c
            WHERE CAST(:search AS string) IS NULL
               OR LOWER(c.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
               OR LOWER(c.description) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
            """)
    Page<Connector> search(
            @Param("search") String search,
            Pageable pageable
    );
}
