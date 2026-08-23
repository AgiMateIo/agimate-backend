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

    /**
     * Mail. Separate from the push executor rather than shared: SMTP answers in seconds where a push
     * transport answers in milliseconds, and a slow relay must not take the notifications down with
     * it. The limit is low on purpose — a mailbox rate-limits long before a hundred parallel sends.
     */
    @Bean
    public SimpleAsyncTaskExecutor mailExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("mail-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(10);
        return executor;
    }
}
