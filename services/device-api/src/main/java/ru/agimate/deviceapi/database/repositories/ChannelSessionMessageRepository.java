package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;
import ru.agimate.deviceapi.database.entities.MessageDirection;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelSessionMessageRepository extends JpaRepository<ChannelSessionMessage, Long> {

    List<ChannelSessionMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    Optional<ChannelSessionMessage> findFirstBySessionIdAndDirectionOrderByCreatedAtDesc(
            Long sessionId, MessageDirection direction);
}
