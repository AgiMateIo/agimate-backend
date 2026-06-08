package ru.agimate.userapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.userapi.database.entities.WaitlistEntry;

import java.util.UUID;

@Repository
public interface WaitlistEntryRepository extends JpaRepository<WaitlistEntry, UUID> {
    boolean existsByEmail(String email);
}
