package ru.agimate.agentworker.config;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;
import ru.agimate.agentworker.workers.AgentRunCore;
import ru.agimate.agentworker.workers.AgentRunWorkflow;
import ru.agimate.agentworker.workers.AgentRunWorkflowImpl;
import ru.agimate.agentworker.workers.AgentWorkflow;
import ru.agimate.agentworker.workers.AgentWorkflowImpl;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.LlmCallWorkflowImpl;
import ru.agimate.agentworker.workers.Queues;
import ru.agimate.agentworker.workers.ToolCallWorkflow;
import ru.agimate.agentworker.workers.ToolCallWorkflowImpl;

/**
 * Composition root for the DBOS surface. Builds the {@link DBOS} instance, registers the queues and
 * the workflow proxies (matching control-api's producer contract — {@code AgentWorkflow}/
 * {@code start_agent}/{@code default} on {@code agent_runs}), and launches/stops the executor via
 * Spring's lifecycle. Registration happens at construction (before launch); {@code launch()} runs on
 * {@link #start()} once the context is ready.
 */
@Slf4j
@Component
public class DbosRuntime implements SmartLifecycle {

    private final DBOS dbos;
    private volatile boolean running = false;

    public DbosRuntime(AgentProperties props, AgentWorkerClient client, ModelFactory modelFactory,
                       LlmMessageMapper mapper) {
        AgentProperties.Dbos d = props.getDbos();
        if (d.getDatabaseUrl() == null || d.getDatabaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "agent.dbos.database-url is not set — provide AGENT_DBOS_DATABASE_URL (the shared DBOS Postgres)");
        }

        DBOSConfig config = DBOSConfig.defaults(d.getAppName())
                .withDatabaseUrl(d.getDatabaseUrl())
                .withDatabaseSchema(d.getSchema())
                .withListenQueues(Queues.AGENT_QUEUE, Queues.AGENT_EXEC_QUEUE, Queues.LLM_QUEUE, Queues.TOOL_QUEUE)
                .withMigrate(true);
        if (d.getUsername() != null) {
            config = config.withDbUser(d.getUsername());
        }
        if (d.getPassword() != null) {
            config = config.withDbPassword(d.getPassword());
        }
        if (d.getApplicationVersion() != null && !d.getApplicationVersion().isBlank()) {
            config = config.withAppVersion(d.getApplicationVersion());
        }
        this.dbos = new DBOS(config);

        // Register queues (per-worker concurrency from config). The run queue is partitioned by
        // session with global concurrency=1 → exactly one executing run per session across the fleet.
        AgentProperties.Concurrency c = props.getConcurrency();
        dbos.registerQueue(new Queue(Queues.AGENT_QUEUE).withWorkerConcurrency(c.getAgentRuns()));
        Queue execQueue = new Queue(Queues.AGENT_EXEC_QUEUE)
                .withPartitioningEnabled(true)
                .withConcurrency(1)
                .withWorkerConcurrency(c.getAgentRuns());
        Queue llmQueue = new Queue(Queues.LLM_QUEUE).withWorkerConcurrency(c.getLlm());
        Queue toolQueue = new Queue(Queues.TOOL_QUEUE).withWorkerConcurrency(c.getTool());
        dbos.registerQueue(execQueue);
        dbos.registerQueue(llmQueue);
        dbos.registerQueue(toolQueue);

        // Register workflow proxies. Router (agent_runs) → run stage (agent_exec) → llm/tool workers.
        LlmCallWorkflow llm = dbos.registerProxy(LlmCallWorkflow.class,
                new LlmCallWorkflowImpl(client, modelFactory, mapper), Queues.INSTANCE);
        ToolCallWorkflow tool = dbos.registerProxy(ToolCallWorkflow.class,
                new ToolCallWorkflowImpl(client, dbos), Queues.INSTANCE);
        AgentRunCore core = new AgentRunCore(dbos, client, llm, tool, llmQueue, toolQueue);
        AgentRunWorkflow run = dbos.registerProxy(AgentRunWorkflow.class,
                new AgentRunWorkflowImpl(dbos, client, core, props.getSession()), Queues.INSTANCE);
        dbos.registerProxy(AgentWorkflow.class,
                new AgentWorkflowImpl(dbos, client, run, execQueue, props.getSession()), Queues.INSTANCE);
    }

    @Override
    public void start() {
        dbos.launch();
        running = true;
        log.info("DBOS launched; listening on queues {}, {}, {} (workflow {})",
                Queues.AGENT_QUEUE, Queues.LLM_QUEUE, Queues.TOOL_QUEUE, Queues.AGENT_WORKFLOW);
    }

    @Override
    public void stop() {
        if (running) {
            dbos.shutdown();
            running = false;
            log.info("DBOS shut down");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // Start last / stop first.
        return Integer.MAX_VALUE;
    }
}
