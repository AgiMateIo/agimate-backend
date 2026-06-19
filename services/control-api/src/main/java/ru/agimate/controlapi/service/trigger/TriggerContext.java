package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Продюсерские директивы на триггере (1:1 с событием): что навешано для роутинга и контекста агента.
 * <p>
 * {@code audience} — сужение списка агентов после policy (actor/targets).
 * {@code channels} — declared-форма каналов (заданный prompt-канал и т.п.); {@code sessionId}
 * в {@link ChannelInfo} здесь не заполняется — он per-agent и резолвится в {@code TriggerRoute}.
 * Оба поля опциональны: для обычного входящего вебхука {@code context == null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerContext(TriggerAudience audience, Channels channels) {

    public static TriggerContext audience(TriggerAudience audience) {
        return new TriggerContext(audience, null);
    }
}
