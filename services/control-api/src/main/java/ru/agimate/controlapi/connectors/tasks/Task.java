package ru.agimate.controlapi.connectors.tasks;

/**
 * Тело фоновой задачи. Выполняется backend'ом ({@code LongRunningBackend} / {@code PeriodicBackend} /
 * {@code CronBackend}) и не должно знать, как именно оно запущено.
 *
 * <p>Для {@link TaskDescriptor.LongRunning} реализация обычно содержит собственный цикл и должна
 * проверять {@code Thread.currentThread().isInterrupted()} — backend при стопе делает
 * {@code Thread#interrupt}. Для {@link TaskDescriptor.Periodic} и {@link TaskDescriptor.Cron}
 * вызов одиночный — цикл организует шедулер.
 *
 * <p>Всё, что задаче нужно знать о себе (credentials, identity, userId, дополнительные сервисы),
 * handler захватывает в closure при сборке {@code Task} в {@code getBackgroundTasks(...)}. MDC с
 * {@link TaskKey} ставит backend перед вызовом — логи внутри тела автоматически снабжаются ключом.
 */
@FunctionalInterface
public interface Task {
    void run() throws Exception;
}
