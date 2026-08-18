package ru.agimate.userapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * Push delivery. Virtual threads: the work is waiting on the transport's HTTP endpoint, and the
     * service that reported the event must not be held for it. The limit is pathology insurance, not
     * capacity planning — a person has a handful of devices, not thousands.
     */
    @Bean
    public SimpleAsyncTaskExecutor pushExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("push-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(100);
        return executor;
    }
}
