package ru.agimate.agentworker.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.agent.AgentChatMessage;
import ru.agimate.agentworker.agent.ToolDef;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps between the worker's {@link AgentChatMessage} model and Spring AI's message/tool types.
 * Tool definitions are exposed as {@link ToolCallback}s whose {@code call()} is never invoked —
 * the worker drives tool execution itself (no advisor), so Spring AI only forwards the tool
 * definitions to the model and returns any tool calls for us to dispatch.
 */
@Component
public class LlmMessageMapper {

    public List<Message> toSpringMessages(List<AgentChatMessage> messages) {
        List<Message> out = new ArrayList<>(messages.size());
        for (AgentChatMessage m : messages) {
            switch (m.role()) {
                case SYSTEM -> out.add(new SystemMessage(nullToEmpty(m.text())));
                case USER -> out.add(new UserMessage(nullToEmpty(m.text())));
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
