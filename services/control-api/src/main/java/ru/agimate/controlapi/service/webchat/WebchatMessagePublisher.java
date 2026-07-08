package ru.agimate.controlapi.service.webchat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.database.enums.WebchatMessageDirection;
import ru.agimate.controlapi.database.repositories.WebchatMessageRepository;
import ru.agimate.controlapi.service.centrifugo.CentrifugoService;

import java.time.Instant;
import java.util.UUID;

/**
 * Единая точка доставки webchat-сообщения на фронт: строка в {@code webchat_messages} (UI-история)
 * + событие в Centrifugo {@code webchat:{sessionId}} (live). Используется и для вывода агента
 * ({@code WebchatChannelHandler.handleOutput}), и для echo сообщений пользователя.
 *
 * <p>Строка идемпотентна по {@code (session_id, message_id)}; событие публикуется всегда
 * (at-least-once, включая replay) — фронт дедуплицирует по {@code messageId}, поэтому retry после
 * упавшей публикации не теряет live-доставку.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebchatMessagePublisher {

    public static final String CENTRIFUGO_CHANNEL_PREFIX = "webchat:";
    public static final String EVENT_TYPE = "webchat_message";

    private final WebchatMessageRepository webchatMessageRepository;
    private final CentrifugoService centrifugoService;

    @Transactional
    public void record(UUID userId, UUID agentId, UUID channelId, UUID sessionId,
                       WebchatMessageDirection direction, String stream, String messageId, String text) {
        int inserted = webchatMessageRepository.insertIgnoreConflict(
                userId, agentId, channelId, sessionId, direction.name(), stream, messageId, text);
        if (inserted == 0) {
            log.debug("Webchat message {} already recorded in session {} (replay) - republishing event",
                    messageId, sessionId);
        }
        centrifugoService.publishMessage(
                CENTRIFUGO_CHANNEL_PREFIX + sessionId,
                EVENT_TYPE,
                new WebchatMessageEvent(sessionId, channelId, agentId, messageId,
                        direction.name(), stream, text, Instant.now().toString()));
    }
}
