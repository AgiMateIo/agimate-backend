package ru.agimate.agentworker.workers.run;

import com.openai.errors.OpenAIInvalidDataException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import io.grpc.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.agimate.agentworker.LlmCredentials;
import ru.agimate.agentworker.agent.ResponseTemplates;
import ru.agimate.agentworker.agent.error.LlmResponseIncomplete;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;
import ru.agimate.agentworker.agent.model.LlmMeta;
import ru.agimate.agentworker.agent.model.LlmUsage;
import ru.agimate.agentworker.agent.model.ToolDef;
import ru.agimate.agentworker.config.AgentProperties;
import ru.agimate.agentworker.grpc.AgentWorkerClient;
import ru.agimate.agentworker.grpc.ControlApiCallException;
import ru.agimate.agentworker.llm.LlmMessageMapper;
import ru.agimate.agentworker.llm.ModelFactory;
import ru.agimate.agentworker.llm.StreamAssembler;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One model request, end to end: credentials fetched inline, attachment bytes inline, the streamed
 * provider call with transient-error retries, and error classification. Runs inside the run
 * workflow's {@code llm_call} step ({@link LlmCallDispatcher}); shared across runs, so the
 * {@link Semaphore} bounds concurrent provider requests per worker the way the dedicated LLM queue
 * used to.
 *
 * <p>The answer is read as it is generated ({@code stream: true}). Not for the partial text — the
 * loop still gets one whole turn — but because a request that puts nothing on the wire for minutes
 * is indistinguishable from a dead connection: any timeout on the way, ours included, cuts it, and
 * the retry buys the same generation again. Streaming lets waiting be measured where it means
 * something: the pause between chunks is «stuck», the length of the answer is not.
 *
 * <p>A failure is a {@link Reply} value, never an exception: DBOS would log a thrown one at ERROR
 * with a stack trace, and the dispatcher turns it back into an exception in plain context.
 */
@Slf4j
public class LlmCall {

    /**
     * @param assistant  the parsed reply; {@code null} on failure
     * @param meta       provenance for the ledger (finish reason, model, call id); {@code null} on failure
     * @param usage      token counts for accounting; {@code null} when there is nothing to account
     * @param incomplete why the turn is only part of an answer, {@code null} when it is whole. Set
     *                   here only for a stream that broke after content had arrived — the reasons a
     *                   {@code finish_reason} carries are derived by the dispatcher instead
     * @param statusCode HTTP status of a failed call; {@code null} for a non-HTTP failure
     * @param message    the failure text
     * @param userFacing {@code message} is already a notice for the user (a quota text, «no model
     *                   configured») and must be surfaced verbatim
     */
    public record Reply(AgentChatMessage assistant, LlmMeta meta, LlmUsage usage,
                        LlmResponseIncomplete.Reason incomplete,
                        boolean failed, Integer statusCode, String message, boolean userFacing) {

        static Reply ok(AgentChatMessage assistant, LlmMeta meta, LlmUsage usage,
                        LlmResponseIncomplete.Reason incomplete) {
            return new Reply(assistant, meta, usage, incomplete, false, null, null, false);
        }

        static Reply failure(Integer statusCode, String message) {
            return new Reply(null, null, null, null, true, statusCode, message, false);
        }

        static Reply userError(String message) {
            return new Reply(null, null, null, null, true, null, message, true);
        }
    }

    private final AgentWorkerClient client;
    private final ModelFactory modelFactory;
    private final LlmMessageMapper mapper;
    private final ResponseTemplates templates;
    private final Semaphore slots;
    private final Duration firstChunkTimeout;
    private final Duration idleTimeout;

    public LlmCall(AgentWorkerClient client, ModelFactory modelFactory, LlmMessageMapper mapper,
                   ResponseTemplates templates, int concurrency, AgentProperties.Llm budgets) {
        this.client = client;
        this.modelFactory = modelFactory;
        this.mapper = mapper;
        this.templates = templates;
        this.slots = new Semaphore(concurrency);
        this.firstChunkTimeout = budgets.getFirstChunkTimeout();
        this.idleTimeout = budgets.getIdleTimeout();
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
            // Built by the factory, next to the client: the request body is assembled from these options
            // alone (see ModelFactory's class javadoc).
            OpenAiChatOptions options = modelFactory.requestOptions(creds, mapper.toolCallbacks(toolDefs));
            // Whether to attach pictures inline is decided per call, from the model's input_modalities in the
            // credentials; an empty list means the model registry does not know the model → we attach optimistically.
            boolean imageInput = creds.getInputModalitiesList().isEmpty()
                    || creds.getInputModalitiesList().contains("image");
            if (!imageInput && hasImageParts(messages)) {
                log.info("chat model {} lacks image input — inbound images stay text stubs",
                        creds.getModel());
            }
            // Attachment bytes are pulled inline (like the credentials) — they never enter a checkpoint.
            Map<String, byte[]> mediaBytes = imageInput ? fetchImageBytes(messages, agentId) : Map.of();
            Prompt prompt = new Prompt(mapper.toSpringMessages(messages, mediaBytes, imageInput), options);
            Streamed streamed = streamWithRetry(model, prompt);
            StreamAssembler turn = streamed.turn();
            LlmMeta meta = new LlmMeta(turn.finishReason(), creds.getModel(), callId);
            return Reply.ok(turn.message(callId), meta, buildUsage(turn.usage(), creds, callId),
                    streamed.incomplete());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.failure(null, "interrupted while waiting for the model");
        } catch (Exception e) {
            OpenAIServiceException svc = findServiceException(e);
            if (svc != null) {
                log.warn("LLM HTTP error (status={}): {}", svc.statusCode(), svc.getMessage());
                return Reply.failure(svc.statusCode(), Failures.message(svc));
            }
            log.warn("LLM API error: {}", Failures.detail(e));
            return Reply.failure(null, Failures.detail(e));
        }
    }

    /**
     * Token counts, self-contained for reporting ({@code callId}/{@code model}/{@code providerId}).
     * {@code null} when there is nothing to account for: no usage metadata, or an empty
     * {@code provider_id} (an older control-api during a rolling deploy).
     */
    private static LlmUsage buildUsage(Usage usage, LlmCredentials creds, String callId) {
        if (creds.getProviderId().isBlank()) {
            return null;
        }
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
                    byte[] data = client.getFile(part.fileId(), part.version(), agentId);
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
    /** How often a running stream reports what has arrived — a handful of lines per long answer. */
    private static final long PROGRESS_EVERY_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** What one streaming attempt produced: the assembled turn, and why it is only part of an answer. */
    private record Streamed(StreamAssembler turn, LlmResponseIncomplete.Reason incomplete) {}

    /**
     * The streamed request, retried while nothing has been produced yet. A single provider blip must
     * not kill a run along with the tool work accumulated in it; 4xx other than 429/408 are terminal
     * and go straight to error mapping.
     *
     * <p>Where a break lands decides everything. <b>Before</b> the first content nothing was
     * generated, so repeating costs nothing but time. <b>After</b> it the tokens are spent and the
     * provider will bill them again for the same prompt, so the attempt is kept as it is and the turn
     * is marked incomplete: the text goes into the ledger, and the run ends with the same «the answer
     * was cut off» notice a token-limit truncation produces. A break inside a tool-call sequence
     * lands in the first case by construction — Spring AI holds those deltas until they merge, so a
     * half-written call never reaches us.
     *
     * <p>The concurrency slot is held across the retries: worst case {@value #MAX_ATTEMPTS} attempts,
     * each bounded by the first-chunk budget or by the call ceiling — a deliberate price, and the
     * arithmetic that keeps a run from looking silent to control-api's sweeper (see
     * {@link AgentProperties.Llm}).
     */
    private Streamed streamWithRetry(OpenAiChatModel model, Prompt prompt) throws InterruptedException {
        slots.acquire();
        try {
            long backoffMs = INITIAL_BACKOFF_MS;
            for (int attempt = 1; ; attempt++) {
                StreamAssembler turn = new StreamAssembler(mapper);
                long startedAt = System.nanoTime();
                try {
                    // The two waiting budgets live here rather than on the HTTP client, where a pause
                    // between chunks is not expressible; the socket under them is freed by OkHttp's read
                    // timeout (ModelFactory) — cancelling the subscription alone does not do it, the
                    // SDK's close waits for its reader. The timers sit behind the assembler on purpose:
                    // only a chunk that carried something passes, so an empty prelude or keepalive
                    // does not count as the model producing.
                    // Progress goes to the log while the answer is long: a generation that runs into
                    // the call ceiling looks exactly like a dead connection until the numbers say
                    // otherwise, and «chunks are arriving» is the one thing nobody can tell from outside.
                    AtomicLong nextProgressAt = new AtomicLong(startedAt + PROGRESS_EVERY_NANOS);
                    model.stream(prompt)
                            // The cancel a timeout sends upstream ends in the SDK's close(), which waits
                            // for its reader — up to the read timeout under full silence. Off the timer
                            // thread, so the error reaches blockLast at the budget and the Reactor
                            // scheduler shared by every stream in the process is not parked meanwhile.
                            .cancelOn(Schedulers.boundedElastic())
                            .filter(turn::accept)
                            .timeout(Mono.delay(firstChunkTimeout), chunk -> Mono.delay(idleTimeout))
                            // Reactor's own text names neither the budget nor its length — ours does.
                            .onErrorMap(TimeoutException.class, e -> new TimeoutException(turn.started()
                                    ? "stream idle for " + human(idleTimeout)
                                    : "no first chunk within " + human(firstChunkTimeout)))
                            .doOnNext(chunk -> {
                                long now = System.nanoTime();
                                if (now >= nextProgressAt.get()) {
                                    nextProgressAt.set(now + PROGRESS_EVERY_NANOS);
                                    log.info("LLM stream in progress after {} ms: {}",
                                            elapsedMs(startedAt), turn.summary());
                                }
                            })
                            .blockLast();
                    log.info("LLM stream done after {} ms: {}", elapsedMs(startedAt), turn.summary());
                    return new Streamed(turn, null);
                } catch (Exception e) {
                    long elapsedMs = elapsedMs(startedAt);
                    if (turn.started()) {
                        log.warn("LLM stream broke after {} ms ({}) — keeping the partial turn: {}",
                                elapsedMs, turn.summary(), Failures.detail(e));
                        return new Streamed(turn, LlmResponseIncomplete.Reason.BROKEN);
                    }
                    if (attempt >= MAX_ATTEMPTS || !retryable(e)) {
                        throw e;
                    }
                    long delayMs = Math.max(backoffMs, retryAfterMs(e));
                    log.info("LLM transient error (attempt {}/{}) after {} ms ({}), retrying in {} ms: {}",
                            attempt, MAX_ATTEMPTS, elapsedMs, turn.summary(), delayMs, Failures.detail(e));
                    Thread.sleep(delayMs);
                    backoffMs *= 2;
                }
            }
        } finally {
            slots.release();
        }
    }

    private static long elapsedMs(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String human(Duration budget) {
        return budget.toMillis() < 1_000 ? budget.toMillis() + " ms" : budget.toSeconds() + " s";
    }

    /**
     * Worth another attempt: the provider faltered, or our own waiting budget expired. The second
     * half is ours and not the provider's fault, which is why it is not folded into
     * {@link #transientProviderError} — a timeout says «nothing arrived», not «the provider is
     * broken», and only the first of those is a reason to look at the provider.
     */
    static boolean retryable(Throwable t) {
        if (transientProviderError(t)) {
            return true;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 429/408/5xx and network failures; every other 4xx is terminal. Network covers the SDK's own
     * wrappers and the exceptions of the SSE layer, which reports a body that ended mid-stream as
     * invalid data rather than as an IO failure.
     */
    static boolean transientProviderError(Throwable t) {
        OpenAIServiceException svc = findServiceException(t);
        if (svc != null) {
            int status = svc.statusCode();
            return status == 429 || status == 408 || status >= 500;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof OpenAIIoException || c instanceof OpenAIRetryableException
                    || c instanceof OpenAIInvalidDataException || c instanceof IOException) {
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
