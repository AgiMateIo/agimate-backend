package ru.agimate.agentworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.ZoneOffset;
import java.util.TimeZone;

/**
 * Entry point for the agent-worker.
 *
 * <p>A headless Spring Boot application that consumes work from DBOS
 * queues, runs an agent turn-loop against an LLM and backend tools, and talks to
 * control-api over gRPC. The Java counterpart of the {@code pydantic-dbos-agent}
 * Python worker; the DBOS producer contract (queue/class/workflow/instance names)
 * is fixed by control-api.
 */
@SpringBootApplication
@EnableScheduling
public class AgentWorkerApplication {

    public static void main(String[] args) {
        // Timestamps are stored as UTC without a zone, and LocalDateTime.now() has to agree with them.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(AgentWorkerApplication.class, args);
    }
}
