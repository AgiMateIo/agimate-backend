package ru.agimate.controlapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Executor for connector tools ({@code ExecuteToolAsync} must not hold a gRPC thread). Virtual
     * threads, one per call: a tool spends its life waiting on someone else's IO, and with a detached
     * call that wait is unbounded — a platform pool would make "how long may a tool run" a question
     * about thread supply, which is how the previous 32-thread pool ended up executing tools on HTTP
     * threads under overflow (CallerRuns).
     *
     * <p>The concurrency limit is pathology insurance, not capacity planning — and it is not a queue:
     * at the limit {@code ConcurrencyThrottleSupport} blocks the <em>submitter</em>, same pressure
     * the old CallerRuns applied. Meaningful ceilings live at the entry points (per-agent caps,
     * rate limits), not here.
     */
    @Bean
    public SimpleAsyncTaskExecutor toolExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("tool-exec-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(1000);
        return executor;
    }

    /**
     * Handing notifications over to user-api. Separate from {@link #toolExecutor()} on purpose: this
     * is a short call to a neighbouring service, and sharing a pool with tools — which wait for
     * minutes and are throttled accordingly — would let one starve the other.
     */
    @Bean
    public SimpleAsyncTaskExecutor notificationExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("notify-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(100);
        return executor;
    }
}
