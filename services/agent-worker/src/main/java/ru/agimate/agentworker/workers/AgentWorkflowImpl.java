package ru.agimate.agentworker.workers;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.StepOptions;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.dto.AgentMessage;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;

/**
 * Router: the {@code agent_runs} entry point (протокол v2). Payload несёт только
 * {@code {agentId, runId}}; сессию single-writer'а резолвит бэк в {@code RegisterRun} и
 * возвращает как {@code session_key} — партиционный ключ очереди {@code agent_exec}
 * (concurrency=1 → один исполняющийся ран на сессию). Сообщение в занятую сессию просто ждёт
 * своей очереди — steering'а нет. Claim здесь помечает ран RUNNING на время ожидания (TTL
 * страхует); run-стадия re-affirm'ит его на старте.
 */
@Slf4j
@WorkflowClassName(Queues.AGENT_CLASS)
public class AgentWorkflowImpl implements AgentWorkflow {

    private final DBOS dbos;
    private final AgentWorkerClient client;
    private final AgentRunWorkflow run;
    private final Queue execQueue;
    private final AgentProperties.Session session;

    public AgentWorkflowImpl(DBOS dbos, AgentWorkerClient client, AgentRunWorkflow run, Queue execQueue,
                            AgentProperties.Session session) {
        this.dbos = dbos;
        this.client = client;
        this.run = run;
        this.execQueue = execQueue;
        this.session = session;
    }

    @Override
    @Workflow(name = Queues.AGENT_WORKFLOW)
    public void startAgent(AgentMessage message) {
        // Durable step: the claim outcome is checkpointed (a crash-replay must not re-claim a slot
        // the finished run has already released) and transient gRPC errors get step retries.
        SlotClaim slot = dbos.runStep(
                () -> SlotClaim.from(client.registerRun(
                        message.agentId(), message.runId(), session.getRunTtlSeconds())),
                new StepOptions("register_run").withMaxAttempts(3)
                        .withShouldRetry(ControlApiCallException::retriableInStep));

        String partitionKey = slot.sessionKey().isBlank() ? message.runId() : slot.sessionKey();
        dbos.startWorkflow(() -> run.runAgent(message),
                new StartWorkflowOptions(execQueue)
                        .withWorkflowId(message.runId())
                        .withQueuePartitionKey(partitionKey));
        log.debug("enqueued run {} on {} (slot={}, partition={})",
                message.runId(), Queues.AGENT_EXEC_QUEUE, slot.status(), partitionKey);
    }
}
