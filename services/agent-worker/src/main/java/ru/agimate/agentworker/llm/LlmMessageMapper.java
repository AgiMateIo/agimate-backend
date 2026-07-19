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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maps between the worker's {@link AgentChatMessage} model and Spring AI's message/tool types.
 * Tool definitions are exposed as {@link ToolCallback}s whose {@code call()} is never invoked —
 * the worker drives tool execution itself (no advisor), so Spring AI only forwards the tool
 * definitions to the model and returns any tool calls for us to dispatch.
 */
@Slf4j
@Component
public class LlmMessageMapper {

    public List<Message> toSpringMessages(List<AgentChatMessage> messages) {
        return toSpringMessages(messages, Map.of());
    }

    /**
     * Как {@link #toSpringMessages(List)}, но у user-сообщений с image-вложениями подмешивает
     * {@link Media} из {@code mediaBytes} (fileId → байты, подтянутые GetFile'ом при LLM-вызове).
     * Нет байтов для ссылки (недоступна/не image) → вложение опускается: текст уже несёт стаб.
     */
    public List<Message> toSpringMessages(List<AgentChatMessage> messages, Map<String, byte[]> mediaBytes) {
        List<Message> out = new ArrayList<>(messages.size());
        for (AgentChatMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> out.add(new SystemMessage(nullToEmpty(m.text())));
                case USER -> out.add(userMessage(m, mediaBytes));
                case ASSISTANT -> out.add(AssistantMessage.builder()
                        .content(nullToEmpty(m.text()))
                        .toolCalls(m.toolCalls().stream()
                                .map(tc -> new AssistantMessage.ToolCall(tc.id(), "function", tc.name(), tc.argumentsJson()))
                                .toList())
                        .build());
                case TOOL -> out.add(ToolResponseMessage.builder()
                        .responses(m.toolResults().stream()
                                .map(tr -> new ToolResponseMessage.ToolResponse(tr.id(), tr.name(), tr.contentJson()))
                                .toList())
                        .build());
            }
        }
        return out;
    }

    private static Message userMessage(AgentChatMessage m, Map<String, byte[]> mediaBytes) {
        List<Media> media = new ArrayList<>();
        for (FilePartRef part : m.parts()) {
            byte[] bytes = mediaBytes.get(part.fileId());
            if (!part.isImage() || bytes == null) {
                continue;
            }
            MimeType mimeType = safeMimeType(part.mime());
            if (mimeType == null) {
                log.warn("skipping inbound image {} — unparseable mime '{}'", part.fileId(), part.mime());
                continue;
            }
            media.add(Media.builder().mimeType(mimeType).data(new ByteArrayResource(bytes)).build());
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

    /** Metadata key Spring AI's OpenAI module stores the provider's reasoning content under. */
    private static final String REASONING_CONTENT_KEY = "reasoningContent";

    /** Convert a non-streaming chat response to an assistant message (text + tool calls + thinking). */
    public AgentChatMessage fromResponse(ChatResponse response) {
        AssistantMessage out = response.getResult().getOutput();
        List<AgentChatMessage.ToolCall> toolCalls = out.getToolCalls().stream()
                .map(tc -> new AgentChatMessage.ToolCall(tc.id(), tc.name(), tc.arguments()))
                .toList();
        // Reasoning models (DeepSeek, Ollama, ...) surface their thinking here; the flag drives
        // the 💭 progress marker and the "thinking..." timeline projection.
        boolean thinking = out.getMetadata().get(REASONING_CONTENT_KEY) instanceof String s && !s.isBlank();
        return AgentChatMessage.assistant(out.getText(), thinking, toolCalls);
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
