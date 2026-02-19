package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.TriggerLogAgent;

public interface TriggerLogAgentRepository extends JpaRepository<TriggerLogAgent, Long> {
}
