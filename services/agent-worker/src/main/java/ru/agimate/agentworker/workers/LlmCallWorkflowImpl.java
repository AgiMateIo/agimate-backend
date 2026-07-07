package ru.agimate.agentworker.workers;

import com.openai.errors.OpenAIServiceException;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.agent.AgentChatMessage;
import ru.agimate.agentworker.agent.ToolDef;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;

import java.util.List;

/**
 * LLM worker: one model request per queue item. Credentials are fetched inline (not via a step) so
 * the {@code api_key} never lands in a checkpointed step output. HTTP/API errors are returned as an
 * {@link LlmCallResult} failure rather than thrown, so DBOS does not log them at ERROR.
 */
@Slf4j
@WorkflowClassName(Queues.LLM_CLASS)
public class LlmCallWorkflowImpl implements LlmCallWorkflow {

    private final AgentWorkerClient client;
    private final ModelFactory modelFactory;
    private final LlmMessageMapper mapper;

    public LlmCallWorkflowImpl(AgentWorkerClient client, ModelFactory modelFactory, LlmMessageMapper mapper) {
        this.client = client;
        this.modelFactory = modelFactory;
        this.mapper = mapper;
    }

    @Override
    @Workflow(name = Queues.LLM_WORKFLOW)
    public LlmCallResult llmCall(List<AgentChatMessage> messages, List<ToolDef> toolDefs, String agentId) {
        LlmCredentials creds;
        try {
            creds = client.getLlmCredentials(agentId);
        } catch (StatusRuntimeException e) {
            log.warn("LLM credentials unavailable ({}): {}", e.getStatus().getCode(), e.getStatus().getDescription());
            String detail = e.getStatus().getDescription();
            return LlmCallResult.failure(null, detail != null ? detail : e.getStatus().getCode().toString());
        }
        log.info("LLM credentials: provider={} model={}", creds.getProviderType(), creds.getModel());

        // Model construction stays inside the try: an unsupported provider_type (or a mapping
        // bug) must come back as a failure value, not escape the workflow past the error mapping.
        try {
            OpenAiChatModel model = modelFactory.build(creds);
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .toolCallbacks(mapper.toolCallbacks(toolDefs))
                    .build();
            Prompt prompt = new Prompt(mapper.toSpringMessages(messages), options);
            ChatResponse response = model.call(prompt);
            return LlmCallResult.ok(mapper.fromResponse(response));
        } catch (Exception e) {
            OpenAIServiceException svc = findServiceException(e);
            if (svc != null) {
                log.warn("LLM HTTP error (status={}): {}", svc.statusCode(), svc.getMessage());
                return LlmCallResult.failure(svc.statusCode(), nonBlankMessage(svc));
            }
            log.warn("LLM API error: {}", e.getMessage());
            return LlmCallResult.failure(null, nonBlankMessage(e));
        }
    }

    /** The exception's message, or its class name when the message is absent. */
    private static String nonBlankMessage(Throwable t) {
        String msg = t.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : t.getClass().getSimpleName();
    }

    /** Walk the cause chain for an OpenAI service exception carrying an HTTP status. */
    private static OpenAIServiceException findServiceException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof OpenAIServiceException svc) {
                return svc;
            }
        }
        return null;
    }
}
