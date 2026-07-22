package ru.agimate.agentworker.config;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.config.DBOSConfig;
import dev.dbos.transact.workflow.Queue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;
import ru.agimate.agentworker.workers.run.AgentRunCore;
import ru.agimate.agentworker.workers.AgentRunWorkflow;
import ru.agimate.agentworker.workers.AgentRunWorkflowImpl;
import ru.agimate.agentworker.workers.LlmCallWorkflow;
import ru.agimate.agentworker.workers.LlmCallWorkflowImpl;
import ru.agimate.agentworker.workers.Queues;
import ru.agimate.agentworker.workers.ToolCallWorkflow;
import ru.agimate.agentworker.workers.ToolCallWorkflowImpl;

/**
 * Composition root for the DBOS surface. Builds the {@link DBOS} instance, registers the queues and
 * the workflow proxies (matching control-api's producer contract — {@code AgentRunWorkflow}/
 * {@code run_agent}/{@code default} on the partitioned {@code agent_exec}), and launches/stops the
 * executor via Spring's lifecycle. Registration happens at construction (before launch);
 * {@code launch()} runs on {@link #start()} once the context is ready.
 */
@Slf4j
@Component
public class DbosRuntime implements SmartLifecycle {

    private final DBOS dbos;
    private volatile boolean running = false;

    public DbosRuntime(AgentProperties props, AgentWorkerClient client, ModelFactory modelFactory,
                       LlmMessageMapper mapper, ResponseTemplates templates) {
        AgentProperties.Dbos d = props.getDbos();
        if (d.getDatabaseUrl() == null || d.getDatabaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "agent.dbos.database-url is not set — provide AGENT_DBOS_DATABASE_URL (the shared DBOS Postgres)");
        }

        DBOSConfig config = DBOSConfig.defaults(d.getAppName())
                .withDatabaseUrl(d.getDatabaseUrl())
                .withDatabaseSchema(d.getSchema())
                .withListenQueues(Queues.RUN_QUEUE, Queues.LLM_QUEUE, Queues.TOOL_QUEUE)
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
        // session with concurrency=1: DBOS applies queue limits per partition, so this means
        // exactly one executing run per session across the fleet. No worker-level cap is
        // expressible on a partitioned queue (both concurrency and workerConcurrency are applied
        // per partition — verified in QueuesDAO.startQueuedWorkflows) — per-worker load is
        // bounded downstream by the llm/tool queues, which do the actual work. Unbounded run-stage
        // workflows are cheap: on Java 21 DBOS executes workflows on virtual threads
        // (newVirtualThreadPerTaskExecutor), so a run blocked awaiting its children parks a
        // virtual thread, not a platform one; memory per run is bounded by the tool-output cap.
        AgentProperties.Concurrency c = props.getConcurrency();
        Queue execQueue = new Queue(Queues.RUN_QUEUE)
                .withPartitioningEnabled(true)
                .withConcurrency(1);
        Queue llmQueue = new Queue(Queues.LLM_QUEUE)
                .withWorkerConcurrency(c.getLlm());
        Queue toolQueue = new Queue(Queues.TOOL_QUEUE)
                .withWorkerConcurrency(c.getTool());
        dbos.registerQueue(execQueue);
        dbos.registerQueue(llmQueue);
        dbos.registerQueue(toolQueue);

        // Register workflow proxies. Run stage (agent_exec, enqueued by control-api) → llm/tool workers.
        LlmCallWorkflow llm = dbos.registerProxy(LlmCallWorkflow.class,
                new LlmCallWorkflowImpl(client, modelFactory, mapper), Queues.INSTANCE);
        ToolCallWorkflow tool = dbos.registerProxy(ToolCallWorkflow.class,
                new ToolCallWorkflowImpl(client, dbos, props.getTool()), Queues.INSTANCE);
        AgentRunCore core = new AgentRunCore(dbos, client, llm, tool, llmQueue, toolQueue, templates);
        dbos.registerProxy(AgentRunWorkflow.class,
                new AgentRunWorkflowImpl(core), Queues.INSTANCE);
    }

    /** The launched DBOS instance — for infra jobs using its public admin API (retention). */
    public DBOS dbos() {
        return dbos;
    }

    @Override
    public void start() {
        dbos.launch();
        running = true;
        log.info("DBOS launched; listening on queues {}, {}, {} (workflow {})",
                Queues.RUN_QUEUE, Queues.LLM_QUEUE, Queues.TOOL_QUEUE, Queues.RUN_WORKFLOW);
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
