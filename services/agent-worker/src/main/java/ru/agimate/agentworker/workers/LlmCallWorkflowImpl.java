package ru.agimate.agentworker.workers;

import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import dev.dbos.transact.context.DBOSContext;
import dev.dbos.transact.context.DBOSContextHolder;
import dev.dbos.transact.context.WorkflowInfo;
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
            // Квота исчерпана: сообщение сервера написано для пользователя — отдаём его дословно,
            // а не подменяем generic-нотисом «ошибка модели».
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
            // Runtime-опции НЕ мержатся с default-options модели (Spring AI 2.0 buildRequestPrompt
            // берёт options промпта как есть), а билдер без model подставляет DEFAULT_CHAT_MODEL
            // (gpt-5-mini) — модель из кредов обязана стоять здесь, иначе провайдер получит дефолт.
            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(creds.getModel())
                    .toolCallbacks(mapper.toolCallbacks(toolDefs))
                    .build();
            // Подаём ли картинки инлайном — решается на каждый вызов по input_modalities модели
            // из кредов; пустой список = реестр модель не знает → оптимистично прикладываем.
            boolean imageInput = creds.getInputModalitiesList().isEmpty()
                    || creds.getInputModalitiesList().contains("image");
            if (!imageInput && hasImageParts(messages)) {
                log.info("chat model {} lacks image input — inbound images stay text stubs",
                        creds.getModel());
            }
            // Байты вложений тянем inline (как креды) — в DBOS-чекпоинт входа воркфлоу не попадают.
            Map<String, byte[]> mediaBytes = imageInput ? fetchImageBytes(messages, agentId) : Map.of();
            Prompt prompt = new Prompt(mapper.toSpringMessages(messages, mediaBytes, imageInput), options);
            ChatResponse response = callWithRetry(model, prompt);
            reportUsage(response, creds, agentId);
            return Result.ok(mapper.fromResponse(response), mapper.finishReason(response));
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
     * Best-effort учёт расхода токенов: сбой репорта логируется и не влияет на результат вызова
     * (иначе инфраструктура учёта стала бы источником отказов ранов). Идемпотентность — на бэке
     * по call_id (собственный workflow id этого LLM-вызова, реплей-стабилен). Пустой provider_id
     * (старый control-api при rolling deploy) → репорт пропускается.
     */
    private void reportUsage(ChatResponse response, LlmCredentials creds, String agentId) {
        try {
            if (creds.getProviderId().isBlank()) {
                return;
            }
            Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            if (usage == null) {
                log.warn("LLM response has no usage metadata — skipping usage report");
                return;
            }
            String callId = currentCallId();
            if (callId == null || callId.isBlank()) {
                log.warn("No workflow id in context — skipping usage report");
                return;
            }
            client.reportLlmUsage(callId, agentId, currentRunId(),
                    creds.getProviderId(), creds.getModel(),
                    intOrZero(usage.getPromptTokens()), intOrZero(usage.getCompletionTokens()),
                    intOrZero(usage.getCacheReadInputTokens()), intOrZero(usage.getCacheWriteInputTokens()));
        } catch (Exception e) {
            log.warn("LLM usage report failed (best-effort): {}", e.getMessage());
        }
    }

    /**
     * Байты image-вложений всех user-сообщений запроса ({@code fileId → bytes}) — inline, чтобы не
     * попасть в чекпоинт (как {@code api_key}). Недоступный файл (NOT_FOUND/сбой) пропускается:
     * текст сообщения уже содержит стаб, «зрение» деградирует, ран не падает. На практике parts
     * есть только у последнего user-сообщения — цикл дешёвый.
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

    /** Собственный workflow id LLM-вызова; шов для тестов. */
    String currentCallId() {
        return DBOSContext.workflowId();
    }

    /** Родительский ран-воркфлоу (= runId), если контекст его знает; шов для тестов. */
    String currentRunId() {
        WorkflowInfo parent = DBOSContextHolder.get().getParent();
        return parent != null ? parent.workflowId() : null;
    }

    private static int intOrZero(Number value) {
        return value == null ? 0 : value.intValue();
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final long INITIAL_BACKOFF_MS = 1_000;
    private static final long MAX_RETRY_AFTER_MS = 30_000;

    /**
     * Транзиентные ошибки провайдера (429/5xx/408, сетевые сбои SDK) ретраятся здесь —
     * иначе один блип провайдера убивает весь ран вместе с накопленной работой тулов.
     * Прочие 4xx (401/403/400) терминальны и уходят в маппинг ошибок сразу. Worst case
     * держит слот llm-очереди на {@value #MAX_ATTEMPTS} × request-timeout — осознанная цена.
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

    /** 429/408/5xx и сетевые исключения SDK; остальные 4xx — терминальные. */
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

    /** Retry-After (секунды) из ответа провайдера, с потолком; 0 — нет/не распарсился. */
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
