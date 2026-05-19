package ru.agimate.deviceapi.database.repositories;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChannelSessionMessageRepository extends JpaRepository<ChannelSessionMessage, Long> {

    List<ChannelSessionMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    Optional<ChannelSessionMessage> findBySessionIdAndTurnIdx(Long sessionId, Integer turnIdx);

    List<ChannelSessionMessage> findBySessionIdOrderByTurnIdxAsc(Long sessionId);

    List<ChannelSessionMessage> findBySessionIdOrderByTurnIdxDesc(Long sessionId, Pageable pageable);

    List<ChannelSessionMessage> findBySessionIdAndTurnIdxGreaterThanEqualOrderByTurnIdxAsc(
            Long sessionId, Integer sinceTurn);

    Optional<ChannelSessionMessage> findFirstBySessionIdAndTriggerInputIsNotNullOrderByCreatedAtDesc(
            Long sessionId);
}
