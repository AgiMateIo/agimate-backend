package ru.agimate.controlapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.controlapi.database.entities.Secret;

import java.util.UUID;

@Repository
public interface SecretRepository extends JpaRepository<Secret, UUID> {
}
