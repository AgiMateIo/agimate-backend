package ru.agimate.agentworker.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import lombok.extern.slf4j.Slf4j;
import ru.agimate.agentworker.agent.model.AgentChatMessage;
import ru.agimate.agentworker.agent.model.FilePartRef;
import ru.agimate.agentworker.agent.model.ToolDef;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Maps between the worker's {@link AgentChatMessage} model and Spring AI's message/tool types.
 * Tool definitions are exposed as {@link ToolCallback}s whose {@code call()} is never invoked —
 * the worker drives tool execution itself (no advisor), so Spring AI only forwards the tool
 * definitions to the model and returns any tool calls for us to dispatch.
 */
@Slf4j
@Component
public class LlmMessageMapper {

    /** Alphabet and length of a minted tool call id — see {@link #mintToolCallId(String, int)}. */
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ID_LENGTH = 9;

    /**
     * The «vision» framing for the current call: attachment stubs ({@code MediaStubs} on the backend)
     * are neutral, so it is the worker that explains the «you can / cannot see it» semantics — by
     * analogy with {@code ResponseTemplates.toolOutputGuidance}. Added only when the request carries
     * image attachments.
     */
    static final String IMAGE_VISIBLE_GUIDANCE =
            "Изображения, приложенные к сообщениям, поданы тебе напрямую — ты видишь их сам. "
            + "id (agf_…) из описания файла нужен только чтобы сослаться на файл в инструментах "
            + "или ответе; скачивать или читать по id уже видимую картинку не нужно.";

    static final String IMAGE_NOT_VISIBLE_GUIDANCE =
            "Твоя модель не принимает изображения на вход: приложенные картинки тебе НЕ видны, "
            + "ты видишь только их текстовые описания с id (agf_…). Чтобы узнать содержимое "
            + "картинки, вызови инструмент чтения изображений (например media.read_image) с этим "
            + "id; для редактирования или пересылки передавай id соответствующему инструменту.";

    public List<Message> toSpringMessages(List<AgentChatMessage> messages) {
        return toSpringMessages(messages, Map.of(), true);
    }

    /**
     * Like {@link #toSpringMessages(List)}, but for user messages with image attachments it mixes in
     * {@link Media} from {@code mediaBytes} (fileId → the bytes pulled by GetFile during the LLM
     * call). No bytes for a reference (unavailable or not an image) → the attachment is omitted: the
     * text already carries a stub.
     *
     * <p>{@code imageInputSupported=false} (a chat model with no image in {@code input_modalities}) —
     * media is not mixed in at all, and when image attachments are present the system hint
     * {@link #IMAGE_NOT_VISIBLE_GUIDANCE} is added instead of {@link #IMAGE_VISIBLE_GUIDANCE}.
     */
    public List<Message> toSpringMessages(List<AgentChatMessage> messages, Map<String, byte[]> mediaBytes,
                                          boolean imageInputSupported) {
        List<Message> out = new ArrayList<>(messages.size());
        for (AgentChatMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> out.add(new SystemMessage(nullToEmpty(m.text())));
                case USER -> out.add(userMessage(m, mediaBytes, imageInputSupported));
                case ASSISTANT -> out.add(
                        AssistantMessage.builder()
                                .content(nullToEmpty(m.text()))
                                .toolCalls(m.toolCalls().stream()
                                        .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.argumentsJson()))
                                        .toList()
                                )
                                .build()
                );
                case TOOL -> out.add(
                        ToolResponseMessage.builder()
                                .responses(m.toolResults().stream()
                                        .map(tr -> new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), tr.contentJson()))
                                        .toList()
                                )
                                .build()
                );
            }
        }
        if (hasImageParts(messages)) {
            // After the leading system messages, before the dialogue — the visibility framing for this call.
            out.add(leadingSystemCount(out),
                    new SystemMessage(imageInputSupported ? IMAGE_VISIBLE_GUIDANCE : IMAGE_NOT_VISIBLE_GUIDANCE));
        }
        return out;
    }

    private static boolean hasImageParts(List<AgentChatMessage> messages) {
        return messages.stream().anyMatch(m -> m.parts().stream().anyMatch(FilePartRef::isImage));
    }

    private static int leadingSystemCount(List<Message> out) {
        int i = 0;
        while (i < out.size() && out.get(i) instanceof SystemMessage) {
            i++;
        }
        return i;
    }

    private static Message userMessage(AgentChatMessage m, Map<String, byte[]> mediaBytes,
                                       boolean imageInputSupported) {
        if (!imageInputSupported) {
            // The model is blind: no media is mixed in, and the text already carries a stub with the id.
            return new UserMessage(nullToEmpty(m.text()));
        }
        List<Media> media = new ArrayList<>();
        for (FilePartRef part : m.parts()) {
            if (!part.isImage()) {
                continue;
            }
            byte[] bytes = mediaBytes.get(part.fileId());
            if (bytes == null || bytes.length == 0) {
                // An image part with no bytes: GetFile failed or came back empty — the model will not see the picture.
                log.warn("inbound image {} has no bytes ({}) — user turn goes text-only",
                        part.fileId(), bytes == null ? "not fetched" : "empty");
                continue;
            }
            MimeType mimeType = safeMimeType(part.mime());
            if (mimeType == null) {
                log.warn("skipping inbound image {} — unparseable mime '{}'", part.fileId(), part.mime());
                continue;
            }
            media.add(Media.builder().mimeType(mimeType).data(new ByteArrayResource(bytes)).build());
            log.info("attached inbound image {} to user turn: {} bytes, mime={}",
                    part.fileId(), bytes.length, mimeType);
        }
        if (media.isEmpty()) {
            return new UserMessage(nullToEmpty(m.text()));
        }
        return UserMessage.builder().text(nullToEmpty(m.text())).media(media).build();
    }

    private static MimeType safeMimeType(String mime) {
        try {
            return mime == null || mime.isBlank() ? null : MimeType.valueOf(mime);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Metadata key Spring AI's OpenAI module stores the provider's reasoning content under. Its own
     * constant is private, so this is a copy of a contract nothing checks at compile time — hence the
     * fallback in {@link #reasoning}: a rename on a Spring AI upgrade degrades to «found it under
     * another reasoning-named key» instead of silently declaring that no model ever reasons.
     */
    private static final String REASONING_CONTENT_KEY = "reasoningContent";

    /**
     * The provider's {@code finish_reason} for this generation (OpenAI: {@code stop}, {@code length},
     * {@code tool_calls}, {@code content_filter}), or {@code null} when absent. Raw string — the
     * run layer decides which reasons are terminal.
     */
    public String finishReason(ChatResponse response) {
        if (response.getResult() == null || response.getResult().getMetadata() == null) {
            return null;
        }
        return response.getResult().getMetadata().getFinishReason();
    }

    /**
     * Convert a non-streaming chat response to an assistant message (text + tool calls + thinking).
     *
     * @param callId the LLM call's workflow id — the seed the tool call ids are minted from
     */
    public AgentChatMessage fromResponse(ChatResponse response, String callId) {
        AssistantMessage out = response.getResult().getOutput();
        List<AgentChatMessage.ToolCall> toolCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : out.getToolCalls()) {
            toolCalls.add(new AgentChatMessage.ToolCall(
                    mintToolCallId(callId, toolCalls.size()), tc.name(), tc.arguments()));
        }
        // Only the flag lives on the message (it drives the 💭 progress marker); the reasoning text
        // itself travels on LlmMeta — see reasoning(ChatResponse).
        return AgentChatMessage.assistant(out.getText(), reasoning(response) != null, toolCalls);
    }

    /**
     * Our own tool call id, minted in place of the one the provider sent: an id coming back from a
     * model is data, not a key. Several OpenAI-compatible servers number tool calls positionally
     * ({@code call_0}, {@code call_1}) and restart the counter every response, so the same id
     * returns carrying different arguments — while the backend keys tool call idempotency on it,
     * per agent, for the agent's whole life. Minted always rather than when an id looks suspicious:
     * the repeating shape differs per stack (sglang, vLLM and Bedrock have each produced one), and
     * «looks unique» is not «is unique».
     *
     * <p>Derived from the LLM call's workflow id, so a DBOS replay of that call mints the same ids
     * for the same calls and the tool workflows keep deduplicating against the rows they created.
     *
     * <p>Nine alphanumerics is the intersection of what providers accept: Mistral rejects anything
     * but {@code [a-zA-Z0-9]{9}} — an underscore included, so OpenAI's own {@code call_…} fails
     * there — OpenAI caps the id at 40 characters, and the rest do not look. 62^9 ≈ 1.4e16 makes a
     * collision unreachable across one agent's calls, which is the whole scope an id has to be
     * unique in: the backend addresses a call by {@code (agent_id, external_id)}. That address is
     * what nine characters are enough for — a lookup by the id alone would not be safe at this
     * length.
     *
     * @param callId blank outside DBOS, where there is no replay to stay consistent with and a
     *               random seed does just as well
     */
    static String mintToolCallId(String callId, int index) {
        String seed = (callId == null || callId.isBlank() ? UUID.randomUUID().toString() : callId)
                + ":" + index;
        BigInteger value = new BigInteger(1, sha256(seed));
        BigInteger base = BigInteger.valueOf(ID_ALPHABET.length());
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(value.mod(base).intValue()));
            value = value.divide(base);
        }
        return id.toString();
    }

    private static byte[] sha256(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * The model's reasoning content for this turn, or {@code null} when it did not reason (the
     * common case: most providers return reasoning only when the request asks for it). Reasoning
     * models — DeepSeek, Ollama and the gateways in front of them — surface it in the assistant
     * metadata.
     *
     * <p>Spring AI always puts {@link #REASONING_CONTENT_KEY} there (an empty string when the
     * provider sent none), so its presence settles the answer; only when the key is absent entirely
     * do we scan for another reasoning-named string — that is the shape a rename upstream would take.
     */
    public String reasoning(ChatResponse response) {
        Map<String, Object> metadata = response.getResult().getOutput().getMetadata();
        Object exact = metadata.get(REASONING_CONTENT_KEY);
        if (exact != null) {
            return exact instanceof String s && !s.isBlank() ? s : null;
        }
        return metadata.entrySet().stream()
                .filter(e -> e.getKey().toLowerCase().contains("reasoning"))
                .map(Map.Entry::getValue)
                .filter(v -> v instanceof String s && !s.isBlank())
                .map(String.class::cast)
                .findFirst()
                .orElse(null);
    }

    /** Tool definitions as no-op callbacks (execution is manual; the callback body is never called). */
    public List<ToolCallback> toolCallbacks(List<ToolDef> defs) {
        List<ToolCallback> callbacks = new ArrayList<>(defs.size());
        for (ToolDef d : defs) {
            ToolDefinition def = DefaultToolDefinition.builder()
                    .name(d.name())
                    .description(nullToEmpty(d.description()))
                    .inputSchema(d.parametersJsonSchema())
                    .build();
            callbacks.add(new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return def;
                }

                @Override
                public String call(String toolInput) {
                    throw new UnsupportedOperationException(
                            "tool execution is driven by the worker, not Spring AI");
                }
            });
        }
        return callbacks;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
