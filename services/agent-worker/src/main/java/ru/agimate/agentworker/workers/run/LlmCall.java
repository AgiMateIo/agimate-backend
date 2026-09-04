package ru.agimate.agentworker.workers.run;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * One model request, end to end: credentials fetched inline, attachment bytes inline, the
 * provider call with transient-error retries, and error classification. Runs inside the run
 * workflow's {@code llm_call} step ({@link LlmCallDispatcher}); shared across runs, so the
 * {@link Semaphore} bounds concurrent provider requests per worker the way the dedicated LLM queue
 * used to.
 *
 * <p>A failure is a {@link Reply} value, never an exception: DBOS would log a thrown one at ERROR
 * with a stack trace, and the dispatcher turns it back into an exception in plain context.
 */
@Slf4j
public class LlmCall {

    /**
     * @param assistant  the parsed reply; {@code null} on failure
     * @param meta       provenance for the ledger (finish reason, model, call id, reasoning); {@code null} on failure
     * @param usage      token counts for accounting; {@code null} when there is nothing to account
     * @param statusCode HTTP status of a failed call; {@code null} for a non-HTTP failure
     * @param message    the failure text
     * @param userFacing {@code message} is already a notice for the user (a quota text, «no model
     *                   configured») and must be surfaced verbatim
     */
    public record Reply(AgentChatMessage assistant, LlmMeta meta, LlmUsage usage,
                        boolean failed, Integer statusCode, String message, boolean userFacing) {

        static Reply ok(AgentChatMessage assistant, LlmMeta meta, LlmUsage usage) {
            return new Reply(assistant, meta, usage, false, null, null, false);
        }

        static Reply failure(Integer statusCode, String message) {
            return new Reply(null, null, null, true, statusCode, message, false);
        }

        static Reply userError(String message) {
            return new Reply(null, null, null, true, null, message, true);
        }
    }

    private final AgentWorkerClient client;
    private final ModelFactory modelFactory;
    private final LlmMessageMapper mapper;
    private final ResponseTemplates templates;
    private final Semaphore slots;

    public LlmCall(AgentWorkerClient client, ModelFactory modelFactory, LlmMessageMapper mapper,
                   ResponseTemplates templates, int concurrency) {
        this.client = client;
        this.modelFactory = modelFactory;
        this.mapper = mapper;
        this.templates = templates;
        this.slots = new Semaphore(concurrency);
    }

    /** @param callId minted by the caller, stable across replays — seeds the tool call ids and keys the usage row */
    public Reply call(List<AgentChatMessage> messages, List<ToolDef> toolDefs, String agentId, String callId) {
        LlmCredentials creds;
        try {
            creds = client.getLlmCredentials(agentId);
        } catch (ControlApiCallException e) {
            // The quota is spent: the server's message is written for the user — we pass it through verbatim
            // rather than substituting a generic «model error» notice.
            if (e.code() == Status.Code.RESOURCE_EXHAUSTED
                    && e.description() != null && !e.description().isBlank()) {
                log.info("LLM quota exceeded: {}", e.description());
                return Reply.userError(e.description());
            }
            // NOT_FOUND (no binding / the bound model is gone) and FAILED_PRECONDITION (the provider is
            // switched off) are all «the agent has no model», which the owner fixes in settings — a generic
            // «model error, try again» sends them nowhere. The server's own text is developer-facing
            // (agent uuids, provider internals), so the notice is authored here instead of passed through.
            if (e.code() == Status.Code.NOT_FOUND || e.code() == Status.Code.FAILED_PRECONDITION) {
                log.warn("no usable chat model for agent {}: {}", agentId, e.getMessage());
                return Reply.userError(templates.noModel());
            }
            log.warn("LLM credentials unavailable: {}", e.getMessage());
            return Reply.failure(null, e.getMessage());
        }
        log.debug("LLM credentials: provider={} model={}", creds.getProviderType(), creds.getModel());

        // Model construction stays inside the try: an unsupported provider_type (or a mapping
        // bug) must come back as a failure value, not escape past the error mapping.
        try {
            OpenAiChatModel model = modelFactory.build(creds);
            // The request body is assembled from these options alone — Spring AI 2.0 does not merge them
            // with the model's defaults — so they are built by the factory, next to the client (a body field
            // set only on the client's defaults never leaves; that is how extra_body used to go missing).
            OpenAiChatOptions options = modelFactory.requestOptions(creds, mapper.toolCallbacks(toolDefs));
            // Whether to attach pictures inline is decided per call, from the model's input_modalities in the
            // credentials; an empty list means the registry does not know the model → we attach optimistically.
            boolean imageInput = creds.getInputModalitiesList().isEmpty()
                    || creds.getInputModalitiesList().contains("image");
            if (!imageInput && hasImageParts(messages)) {
                log.info("chat model {} lacks image input — inbound images stay text stubs",
                        creds.getModel());
            }
            // Attachment bytes are pulled inline (like the credentials) — they never enter a checkpoint.
            Map<String, byte[]> mediaBytes = imageInput ? fetchImageBytes(messages, agentId) : Map.of();
            Prompt prompt = new Prompt(mapper.toSpringMessages(messages, mediaBytes, imageInput), options);
            ChatResponse response = callWithRetry(model, prompt);
            LlmMeta meta = new LlmMeta(mapper.finishReason(response), creds.getModel(), callId,
                    mapper.reasoning(response));
            return Reply.ok(mapper.fromResponse(response, callId), meta, buildUsage(response, creds, callId));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.failure(null, "interrupted while waiting for the model");
        } catch (Exception e) {
            OpenAIServiceException svc = findServiceException(e);
            if (svc != null) {
                log.warn("LLM HTTP error (status={}): {}", svc.statusCode(), svc.getMessage());
                return Reply.failure(svc.statusCode(), nonBlankMessage(svc));
            }
            log.warn("LLM API error: {}", e.getMessage());
            return Reply.failure(null, nonBlankMessage(e));
        }
    }

    /**
     * Token counts, self-contained for reporting ({@code callId}/{@code model}/{@code providerId}).
     * {@code null} when there is nothing to account for: no usage metadata, or an empty
     * {@code provider_id} (an older control-api during a rolling deploy).
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

    private static boolean hasImageParts(List<AgentChatMessage> messages) {
        return messages.stream().anyMatch(m -> m.parts().stream().anyMatch(FilePartRef::isImage));
    }

    /**
     * Image attachment bytes for every user message in the request ({@code fileId → bytes}). An
     * unavailable file (NOT_FOUND or a failure) is skipped: the message text already carries a stub,
     * so «vision» degrades but the run does not fail. In practice only the last user message has
     * parts — the loop is cheap.
     */
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

    private static int intOrZero(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_RETRY_AFTER_MS = 30_000;

    /**
     * Transient provider errors (429/5xx/408, SDK network failures) are retried here — otherwise a
     * single provider blip kills the whole run along with the tool work accumulated in it. Other 4xx
     * (401/403/400) are terminal and go straight to error mapping. The concurrency slot is held
     * across the retries: worst case {@value #MAX_ATTEMPTS} × request-timeout — a deliberate price.
     */
    private ChatResponse callWithRetry(OpenAiChatModel model, Prompt prompt) throws InterruptedException {
        slots.acquire();
        try {
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
                    Thread.sleep(delayMs);
                    backoffMs *= 2;
                }
            }
        } finally {
            slots.release();
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
