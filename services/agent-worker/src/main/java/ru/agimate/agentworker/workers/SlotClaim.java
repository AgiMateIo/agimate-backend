package ru.agimate.agentworker.workers;

import ru.agimate.agentworker.RegisterRunResponse;
import ru.agimate.agentworker.RunSlotStatus;

/**
 * Сериализуемый чекпоинт шага {@code register_run}: proto-типы в результат durable-шага класть
 * нельзя — DBOS (PORTABLE) сериализует его Jackson'ом, а protobuf-сообщения с их рекурсивными
 * дескрипторами Jackson не переваривает.
 */
public record SlotClaim(String status, String sessionKey) {

    public static SlotClaim from(RegisterRunResponse response) {
        return new SlotClaim(response.getStatus().name(), response.getSessionKey());
    }

    public boolean busy() {
        return RunSlotStatus.RUN_SLOT_STATUS_BUSY.name().equals(status);
    }

    public boolean acquired() {
        return RunSlotStatus.RUN_SLOT_STATUS_ACQUIRED.name().equals(status);
    }
}
