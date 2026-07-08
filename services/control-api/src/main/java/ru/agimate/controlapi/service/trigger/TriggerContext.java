package ru.agimate.controlapi.service.trigger;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Продюсерские директивы на триггере (1:1 с событием): что навешано для роутинга и контекста агента.
 * <p>
 * {@code audience} — сужение списка агентов после policy (actor/targets).
 * {@code channels} — declared-форма каналов (заданный prompt-канал и т.п.). {@code sessionId} в
 * prompt-{@link ChannelInfo} обычно пуст (он per-agent и резолвится в {@code TriggerRoute}), но
 * продюсер, знающий сессию своего канала (webchat: фронт выбирает её явно), может задать его —
 * {@code ChannelRouteResolver} использует открытую объявленную сессию вместо TTL-эвристики.
 * Оба поля опциональны: для обычного входящего вебхука {@code context == null}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerContext(TriggerAudience audience, Channels channels) {

    public static TriggerContext audience(TriggerAudience audience) {
        return new TriggerContext(audience, null);
    }
}
