package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.Platform;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformRepository extends JpaRepository<Platform, Long> {

    Optional<Platform> findByCode(String code);

    @Query("SELECT p FROM Platform p WHERE p.enabled = true ORDER BY p.name")
    List<Platform> findAllEnabled();
}
