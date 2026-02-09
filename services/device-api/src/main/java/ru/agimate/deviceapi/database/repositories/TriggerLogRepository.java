package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.agimate.deviceapi.database.entities.TriggerLog;

public interface TriggerLogRepository extends JpaRepository<TriggerLog, Long> {
}
