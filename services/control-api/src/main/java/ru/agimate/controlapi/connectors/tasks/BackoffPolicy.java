package ru.agimate.controlapi.connectors.tasks;

import java.time.Duration;

/**
 * Параметры экспоненциального backoff между итерациями {@link TaskDescriptor.LongRunning},
 * если предыдущая итерация бросила исключение.
 *
 * <p>Формула: {@code delay(n) = min(initial * multiplier^(n-1), max)}.
 * После успешной итерации счётчик сбрасывается.
 *
 * <p>Backoff для специфичных классов ошибок (например, конфликты на стороне внешнего API) —
 * ответственность самой задачи: она ловит нужное исключение и спит сколько считает нужным,
 * не выкидывая его выше.
 *
 * @param initial    задержка после первой ошибки
 * @param max        верхний предел задержки
 * @param multiplier мультипликатор экспоненты
 */
public record BackoffPolicy(Duration initial, Duration max, double multiplier) {

    /** Дефолт: 5s → 60s, ×2. */
    public static final BackoffPolicy DEFAULT = new BackoffPolicy(
            Duration.ofSeconds(5),
            Duration.ofSeconds(60),
            2.0
    );
}
