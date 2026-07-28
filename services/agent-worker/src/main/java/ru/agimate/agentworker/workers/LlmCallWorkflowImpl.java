package ru.agimate.agentworker.workers;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import dev.dbos.transact.context.DBOSContext;
import io.grpc.Status;
import dev.dbos.transact.workflow.Workflow;
import dev.dbos.transact.workflow.WorkflowClassName;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM worker: one model request per queue item. Credentials are fetched inline (not via a step) so
 * the {@code api_key} never lands in a checkpointed step output. HTTP/API errors are returned as an
 * {@link Result} failure rather than thrown, so DBOS does not log them at ERROR.
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
    public Result llmCall(List<AgentChatMessage> messages, List<ToolDef> toolDefs, String agentId) {
        LlmCredentials creds;
        try {
            creds = client.getLlmCredentials(agentId);
        } catch (ControlApiCallException e) {
            // The quota is spent: the server's message is written for the user — we pass it through verbatim
            // rather than substituting a generic «model error» notice.
            if (e.code() == Status.Code.RESOURCE_EXHAUSTED
                    && e.description() != null && !e.description().isBlank()) {
                log.info("LLM quota exceeded: {}", e.description());
                return Result.userError(e.description());
            }
            log.warn("LLM credentials unavailable: {}", e.getMessage());
            return Result.failure(null, e.getMessage());
        }
        log.debug("LLM credentials: provider={} model={}", creds.getProviderType(), creds.getModel());

        // Model construction stays inside the try: an unsupported provider_type (or a mapping
        // bug) must come back as a failure value, not escape the workflow past the error mapping.
        try {
            OpenAiChatModel model = modelFactory.build(creds);
            // Runtime options are NOT merged with the model's default options (Spring AI 2.0 buildRequestPrompt
            // takes the prompt's options as-is), and a builder without model substitutes DEFAULT_CHAT_MODEL
            // (gpt-5-mini) — so the model from the credentials must be set right here, or the provider gets the default.
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(creds.getModel())
                    .toolCallbacks(mapper.toolCallbacks(toolDefs))
                    .build();
            // Whether to attach pictures inline is decided per call, from the model's input_modalities in the
            // credentials; an empty list means the registry does not know the model → we attach optimistically.
            boolean imageInput = creds.getInputModalitiesList().isEmpty()
                    || creds.getInputModalitiesList().contains("image");
            if (!imageInput && hasImageParts(messages)) {
                log.info("chat model {} lacks image input — inbound images stay text stubs",
                        creds.getModel());
            }
            // Attachment bytes are pulled inline (like the credentials) — they never enter the workflow input's DBOS checkpoint.
            Map<String, byte[]> mediaBytes = imageInput ? fetchImageBytes(messages, agentId) : Map.of();
            Prompt prompt = new Prompt(mapper.toSpringMessages(messages, mediaBytes, imageInput), options);
            ChatResponse response = callWithRetry(model, prompt);
            String callId = currentCallId();
            return Result.ok(mapper.fromResponse(response), mapper.finishReason(response),
                    creds.getModel(), callId, buildUsage(response, creds, callId));
        } catch (Exception e) {
            OpenAIServiceException svc = findServiceException(e);
            if (svc != null) {
                log.warn("LLM HTTP error (status={}): {}", svc.statusCode(), svc.getMessage());
                return Result.failure(svc.statusCode(), nonBlankMessage(svc));
            }
            log.warn("LLM API error: {}", e.getMessage());
            return Result.failure(null, nonBlankMessage(e));
        }
    }

    /**
     * The call's tokens for usage accounting — the child workflow only <b>returns</b> them on
     * {@link Result}; the loop surfaces them and the run wiring reports them to the backend (the sole
     * writer of the run's backend-side records). Self-contained for reporting: carries
     * {@code callId}/{@code model}/{@code providerId}. {@code null} when there is nothing to account
     * for: no usage metadata, or an empty {@code provider_id} (an older control-api during a rolling
     * deploy).
     */
    private static LlmUsage buildUsage(ChatResponse response, LlmCredentials creds, String callId) {
        if (creds.getProviderId().isBlank()) {
            return null;
        }
        Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
        if (usage == null) {
            log.warn("LLM response has no usage metadata — skipping usage report");
            return null;
        }
        return new LlmUsage(callId, creds.getProviderId(), creds.getModel(),
                intOrZero(usage.getPromptTokens()), intOrZero(usage.getCompletionTokens()),
                intOrZero(usage.getCacheReadInputTokens()), intOrZero(usage.getCacheWriteInputTokens()));
    }

    /**
     * Image attachment bytes for every user message in the request ({@code fileId → bytes}) — inline,
     * so they stay out of the checkpoint (like {@code api_key}). An unavailable file (NOT_FOUND or a
     * failure) is skipped: the message text already carries a stub, so «vision» degrades but the run
     * does not fail. In practice only the last user message has parts — the loop is cheap.
     */
    private static boolean hasImageParts(List<AgentChatMessage> messages) {
        return messages.stream().anyMatch(m -> m.parts().stream().anyMatch(FilePartRef::isImage));
    }

    private Map<String, byte[]> fetchImageBytes(List<AgentChatMessage> messages, String agentId) {
        Map<String, byte[]> bytes = new LinkedHashMap<>();
        for (AgentChatMessage m : messages) {
            for (FilePartRef part : m.parts()) {
                if (!part.isImage() || bytes.containsKey(part.fileId())) {
                    continue;
                }
                try {
                    byte[] data = client.getFile(part.fileId(), agentId);
                    bytes.put(part.fileId(), data);
                    log.info("inbound image {} fetched: {} bytes (mime={})",
                            part.fileId(), data.length, part.mime());
                } catch (Exception e) {
                    log.warn("inbound image {} unavailable — sending text only: {}",
                            part.fileId(), e.getMessage());
                }
            }
        }
        return bytes;
    }

    /** The LLM call's own workflow id; a seam for tests. */
    String currentCallId() {
        return DBOSContext.workflowId();
    }

    private static int intOrZero(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_RETRY_AFTER_MS = 30_000;

    /**
     * Transient provider errors (429/5xx/408, SDK network failures) are retried here — otherwise a
     * single provider blip kills the whole run along with the tool work accumulated in it. Other 4xx
     * (401/403/400) are terminal and go straight to error mapping. Worst case this holds an llm-queue
     * slot for {@value #MAX_ATTEMPTS} × request-timeout — a deliberate price.
     */
    private static ChatResponse callWithRetry(OpenAiChatModel model, Prompt prompt) {
        long backoffMs = INITIAL_BACKOFF_MS;
        for (int attempt = 1; ; attempt++) {
            try {
                return model.call(prompt);
            } catch (Exception e) {
                if (attempt >= MAX_ATTEMPTS || !transientProviderError(e)) {
                    throw e;
                }
                long delayMs = Math.max(backoffMs, retryAfterMs(e));
                log.info("LLM transient error (attempt {}/{}), retrying in {} ms: {}",
                        attempt, MAX_ATTEMPTS, delayMs, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
                backoffMs *= 2;
            }
        }
    }

    /** 429/408/5xx and SDK network exceptions; every other 4xx is terminal. */
    static boolean transientProviderError(Throwable t) {
        OpenAIServiceException svc = findServiceException(t);
        if (svc != null) {
            int status = svc.statusCode();
            return status == 429 || status == 408 || status >= 500;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof OpenAIIoException || c instanceof OpenAIRetryableException) {
                return true;
            }
        }
        return false;
    }

    /** Retry-After (seconds) from the provider's response, capped; 0 — absent or unparseable. */
    static long retryAfterMs(Throwable t) {
        OpenAIServiceException svc = findServiceException(t);
        if (svc == null) {
            return 0;
        }
        try {
            var values = svc.headers().values("retry-after");
            return values.isEmpty() ? 0
                    : Math.min(Long.parseLong(values.get(0).trim()) * 1000, MAX_RETRY_AFTER_MS);
        } catch (Exception e) {
            return 0;
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
