package ru.agimate.deviceapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.ChannelSession;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;
import ru.agimate.deviceapi.database.entities.MessageDirection;
import ru.agimate.deviceapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.deviceapi.service.trigger.Trigger;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelMessageInboundService {

    private final ChannelSessionService channelSessionService;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;

    public record InboundResult(ChannelSession session, ChannelSessionMessage message) {}

    @Transactional
    public InboundResult process(Channel channel, Trigger trigger) {
        Map<String, Object> data = trigger.data();
        String text = extractMessage(channel.getTriggerMessageField(), data);

        ChannelSession session = channelSessionService.findOrCreateActive(channel, text);

        ChannelSessionMessage message = ChannelSessionMessage.builder()
                .sessionId(session.getId())
                .direction(MessageDirection.IN)
                .message(text != null ? text : "")
                .triggerInput(data)
                .build();
        ChannelSessionMessage saved = channelSessionMessageRepository.save(message);

        channelSessionService.bumpLastMessageAt(session);

        log.debug("Saved IN message pubId={} session={} channel={}",
                saved.getPubId(), session.getPubId(), channel.getPubId());
        return new InboundResult(session, saved);
    }

    private String extractMessage(String messageField, Map<String, Object> data) {
        Object value = InputFilterEvaluator.resolvePath(data, messageField);
        return value != null ? value.toString() : null;
    }
}
