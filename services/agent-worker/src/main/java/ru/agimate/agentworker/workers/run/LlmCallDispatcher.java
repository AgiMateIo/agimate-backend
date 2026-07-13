package ru.agimate.agentworker.workers.run;

import dev.dbos.transact.DBOS;
import dev.dbos.transact.StartWorkflowOptions;
import dev.dbos.transact.workflow.Queue;
import dev.dbos.transact.workflow.WorkflowHandle;
import ru.agimate.agentworker.agent.SimpleAgent;
import ru.agimate.agentworker.agent.error.LlmCallError;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.workers.LlmCallWorkflow;

import java.util.List;

/**
 * Per-run {@link SimpleAgent.LlmCaller}: enqueues each model request as a child workflow on the
 * llm queue and awaits it. Holds no persistence/output state.
 */
class LlmCallDispatcher implements SimpleAgent.LlmCaller {

    private final DBOS dbos;
    private final LlmCallWorkflow llm;
    private final Queue llmQueue;
    private final String agentId;

    LlmCallDispatcher(DBOS dbos, LlmCallWorkflow llm, Queue llmQueue, String agentId) {
        this.dbos = dbos;
        this.llm = llm;
        this.llmQueue = llmQueue;
        this.agentId = agentId;
    }

    @Override
    public AgentChatMessage call(List<AgentChatMessage> messages, List<ToolDef> toolDefs) {
        WorkflowHandle<LlmCallWorkflow.Result, ? extends Exception> handle =
                dbos.startWorkflow(() -> llm.llmCall(messages, toolDefs, agentId), new StartWorkflowOptions(llmQueue));
        LlmCallWorkflow.Result result = WorkflowHandles.await(handle);
        if (result.failed()) {
            throw new LlmCallError(result.statusCode(), result.message(), result.userFacing());
        }
        return result.assistant();
    }
}
