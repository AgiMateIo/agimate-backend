package ru.agimate.deviceapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.rest.error.NotFoundStatusException;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.deviceapi.database.entities.Agent;
import ru.agimate.deviceapi.database.entities.Channel;
import ru.agimate.deviceapi.database.entities.ChannelSession;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessage;
import ru.agimate.deviceapi.database.entities.ChannelSessionMessageKind;
import ru.agimate.deviceapi.database.repositories.AgentRepository;
import ru.agimate.deviceapi.database.repositories.ChannelRepository;
import ru.agimate.deviceapi.database.repositories.ChannelSessionMessageRepository;
import ru.agimate.deviceapi.database.repositories.ChannelSessionRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgentSessionMessagesService {

    private final AgentRepository agentRepository;
    private final ChannelRepository channelRepository;
    private final ChannelSessionRepository channelSessionRepository;
    private final ChannelSessionMessageRepository channelSessionMessageRepository;
    private final ChannelSessionService channelSessionService;

    public record AppendMessage(
            ChannelSessionMessageKind kind,
            byte[] messageJsonBytes,
            String text,
            byte[] triggerInputJsonBytes
    ) {}

    public record AppendResult(List<Integer> assignedTurnIndices) {}

    @Transactional
    public AppendResult append(UUID agentPubId,
                               UUID sessionPubId,
                               UUID runId,
                               int startingTurnIdx,
                               List<AppendMessage> messages) {
        Agent agent = agentRepository.findByPubId(agentPubId)
                .orElseThrow(() -> new NotFoundStatusException("Agent not found"));
        ChannelSession session = channelSessionRepository.findByPubId(sessionPubId)
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
        Channel channel = channelRepository.findById(session.getChannelId())
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!agentPubId.equals(channel.getAgentPubId())) {
            throw new NotFoundStatusException("Channel session does not belong to this agent");
        }

        List<Integer> assigned = new ArrayList<>(messages.size());
        boolean anyInserted = false;
        for (int i = 0; i < messages.size(); i++) {
            int turnIdx = startingTurnIdx + i;
            AppendMessage am = messages.get(i);

            if (channelSessionMessageRepository.findBySessionIdAndTurnIdx(session.getId(), turnIdx).isPresent()) {
                assigned.add(turnIdx);
                continue;
            }

            Map<String, Object> messageJson = parseJson(am.messageJsonBytes());
            Map<String, Object> triggerInput = (am.triggerInputJsonBytes() != null && am.triggerInputJsonBytes().length > 0)
                    ? parseJson(am.triggerInputJsonBytes())
                    : null;

            ChannelSessionMessage entity = ChannelSessionMessage.builder()
                    .sessionId(session.getId())
                    .agentId(agent.getId())
                    .runId(runId)
                    .turnIdx(turnIdx)
                    .kind(am.kind())
                    .message(am.text())
                    .messageJson(messageJson)
                    .triggerInput(triggerInput)
                    .build();

            if (am.kind() == ChannelSessionMessageKind.RESPONSE) {
                extractUsageInto(entity, messageJson);
            }

            channelSessionMessageRepository.save(entity);
            assigned.add(turnIdx);
            anyInserted = true;
        }

        if (anyInserted) {
            channelSessionService.bumpLastMessageAt(session);
            log.debug("Appended {} message(s) to session={} agent={} run={}",
                    messages.size(), session.getPubId(), agentPubId, runId);
        }
        return new AppendResult(assigned);
    }

    public List<ChannelSessionMessage> getHistory(UUID agentPubId,
                                                  UUID sessionPubId,
                                                  int lastN,
                                                  int sinceTurn) {
        ChannelSession session = channelSessionRepository.findByPubId(sessionPubId)
                .orElseThrow(() -> new NotFoundStatusException("Channel session not found"));
        Channel channel = channelRepository.findById(session.getChannelId())
                .orElseThrow(() -> new NotFoundStatusException("Channel not found"));
        if (!agentPubId.equals(channel.getAgentPubId())) {
            throw new NotFoundStatusException("Channel session does not belong to this agent");
        }
        if (sinceTurn > 0) {
            return channelSessionMessageRepository
                    .findBySessionIdAndTurnIdxGreaterThanEqualOrderByTurnIdxAsc(session.getId(), sinceTurn);
        }
        if (lastN > 0) {
            List<ChannelSessionMessage> desc = channelSessionMessageRepository
                    .findBySessionIdOrderByTurnIdxDesc(session.getId(), PageRequest.of(0, lastN));
            List<ChannelSessionMessage> asc = new ArrayList<>(desc);
            Collections.reverse(asc);
            return asc;
        }
        return channelSessionMessageRepository.findBySessionIdOrderByTurnIdxAsc(session.getId());
    }

    private static Map<String, Object> parseJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Map.of();
        }
        return JsonUtils.fromJsonToMap(new String(bytes, StandardCharsets.UTF_8));
    }

    private static void extractUsageInto(ChannelSessionMessage entity, Map<String, Object> json) {
        Object usageObj = json.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            entity.setInputTokens(intOrNull(usage.get("input_tokens")));
            entity.setOutputTokens(intOrNull(usage.get("output_tokens")));
            entity.setCacheReadTokens(intOrNull(usage.get("cache_read_tokens")));
            entity.setCacheWriteTokens(intOrNull(usage.get("cache_write_tokens")));
        }
        Object modelName = json.get("model_name");
        if (modelName instanceof String s) {
            entity.setModelName(s);
        }
        Object providerName = json.get("provider_name");
        if (providerName instanceof String s) {
            entity.setProviderName(s);
        }
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        return null;
    }
}
