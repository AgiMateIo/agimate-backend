package ru.agimate.connectorsapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.agimate.connectorsapi.database.entities.EventDescription;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventDescriptionRepository extends JpaRepository<EventDescription, Long> {

    Optional<EventDescription> findByEventType(String eventType);

    @Query("SELECT e FROM EventDescription e WHERE e.eventType LIKE %:pattern% ORDER BY e.eventType")
    List<EventDescription> findByEventTypeLike(@Param("pattern") String pattern);

    @Query("SELECT e FROM EventDescription e ORDER BY e.eventType")
    List<EventDescription> findAllOrdered();
}
