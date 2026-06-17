package ru.agimate.controlapi.service.channel;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.database.entities.ToolUseLog;
import ru.agimate.controlapi.database.repositories.ToolUseLogRepository;
import ru.agimate.controlapi.service.ConnectorService;

import java.util.Map;
import java.util.UUID;

/**
 * Общая точка диспатча исходящего вызова тула для channel-handler'ов: регистрирует
 * {@code ToolUseLog} (идемпотентно по {@code toolUseId}+{@code agentId}, эффект ALLOW —
 * канал авторизован самим фактом существования) и отправляет в коннектор.
 *
 * <p>Вынесено из handler'ов, чтобы не дублировать логику персиста/отправки в каждой реализации.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelOutboundDispatcher {

    private final ToolUseLogRepository toolUseLogRepository;
    private final ConnectorService connectorService;

    @Transactional
    public ToolUseLog dispatch(UUID agentId, UUID userId, String connectorCode, String identity,
                               String toolName, Map<String, Object> args, String toolCallId) {
        String effectiveToolCallId = toolCallId != null && !toolCallId.isBlank()
                ? toolCallId : UUID.randomUUID().toString();

        ToolUseLog toolUseLog = toolUseLogRepository
                .findByToolUseIdAndAgentId(effectiveToolCallId, agentId)
                .orElseGet(() -> toolUseLogRepository.save(ToolUseLog.builder()
                        .agentId(agentId)
                        .userId(userId)
                        .connectorCode(connectorCode)
                        .identity(identity)
                        .toolUseId(effectiveToolCallId)
                        .toolName(toolName)
                        .input(args)
                        .accessEffect(AccessEffect.ALLOW)
                        .build()));

        connectorService.pushToConnector(toolUseLog);
        log.info("Dispatched OUT tool={} connector={} identity={} agent={}",
                toolName, connectorCode, identity, agentId);
        return toolUseLog;
    }
}
