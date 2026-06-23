package ru.agimate.controlapi.connectors.internal.persistentmemory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import ru.agimate.controlapi.abac.AccessEffect;
import ru.agimate.controlapi.abac.AgentToolPolicyChangedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorCreatedEvent;
import ru.agimate.controlapi.connectors.core.events.ConnectorDeletedEvent;
import ru.agimate.controlapi.database.entities.AgentToolPolicy;
import ru.agimate.controlapi.database.repositories.AgentToolPolicyRepository;

import java.util.UUID;

/**
 * Включение persistent memory на агента. «Память включена» = у агента есть ALLOW-политика на
 * коннектор {@code persist-memory}. Слушает generic {@link AgentToolPolicyChangedEvent} и транслирует
 * текущее состояние политик в lifecycle-событие экземпляра коннектора с {@code identity = agentId}:
 * {@link ConnectorCreatedEvent} (зарегистрировать daily/consolidation джобы) либо
 * {@link ConnectorDeletedEvent} (убрать их). Оба пути идемпотентны (upsert/delete в
 * {@code ConnectorIdentityListener}), поэтому повторные срабатывания безопасны.
 *
 * <p>{@code AFTER_COMMIT} + {@code fallbackExecution} — событие издаётся после commit'а транзакции
 * политики; издаваемый отсюда connector-lifecycle-event ловит {@code ConnectorIdentityListener}
 * по тому же fallback-пути (вне транзакции).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryEnablementListener {

    private final AgentToolPolicyRepository agentToolPolicyRepository;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPolicyChanged(AgentToolPolicyChangedEvent event) {
        UUID agentId = event.agentId();
        boolean enabled = agentToolPolicyRepository.findByAgentId(agentId).stream()
                .anyMatch(MemoryEnablementListener::isMemoryAllow);

        if (enabled) {
            eventPublisher.publishEvent(new ConnectorCreatedEvent(
                    PersistentMemoryConnectorService.CONNECTOR_CODE, agentId.toString(), event.userId()));
            log.info("persist-memory enabled for agent {} — jobs registered", agentId);
        } else {
            eventPublisher.publishEvent(new ConnectorDeletedEvent(
                    PersistentMemoryConnectorService.CONNECTOR_CODE, agentId.toString()));
            log.info("persist-memory not enabled for agent {} — jobs removed", agentId);
        }
    }

    private static boolean isMemoryAllow(AgentToolPolicy policy) {
        return policy.getEffect() == AccessEffect.ALLOW
                && PersistentMemoryConnectorService.CONNECTOR_CODE.equals(policy.getConnectorCode());
    }
}
