package ru.agimate.agentworker.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.agimate.common.util.JsonUtils;

import java.io.IOException;

/**
 * Rewrites one chat/completions request body into the shape a thinking provider validates:
 * {@code reasoning_content} on every assistant message, an empty string where nothing was thought.
 *
 * <p>DeepSeek validates the history structurally once the request carries {@code tools} — a single
 * assistant message without the field answers 400 «The reasoning_content in the thinking mode must be
 * passed back to the API». The provider itself returns an empty string on many tool-calling turns,
 * and the worker reads a blank reasoning as «did not reason», dropping the field. Spring AI cannot put
 * it back either: its outbound converter skips a blank {@code metadata["reasoningContent"]}
 * ({@code StringUtils.hasText}). Hence the body itself is patched at the HTTP layer.
 *
 * <p>The gate is evidence rather than configuration: the patch runs only when the request already
 * carries at least one non-empty {@code reasoning_content}. That proves the provider speaks the field,
 * so providers that never reason are never touched and no per-provider policy has to reach the worker
 * yet — the provider axis is still future work in
 * {@code docs/decisions/provider-turn-envelope.md}. The gap this leaves is a request whose whole
 * visible history has no non-empty reasoning: the field is not added there, and a strict provider can
 * still answer 400.
 */
final class ReasoningEcho {

    private static final String REASONING_CONTENT = "reasoning_content";
    private static final String ASSISTANT = "assistant";
    private static final String MESSAGES = "messages";
    private static final String TOOLS = "tools";

    private ReasoningEcho() {
    }

    /**
     * @return the patched body, or {@code null} when nothing changed — not a chat request, no tools,
     * no reasoning to prove the dialect, or every assistant turn already carries the field
     * @throws IOException the body is not JSON; the caller keeps the original bytes
     */
    static byte[] patch(byte[] body) throws IOException {
        JsonNode root = JsonUtils.MAPPER.readTree(body);
        if (!(root instanceof ObjectNode request)) {
            return null;
        }
        JsonNode tools = request.get(TOOLS);
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return null;
        }
        JsonNode messages = request.get(MESSAGES);
        if (messages == null || !messages.isArray() || !carriesReasoning(messages)) {
            return null;
        }
        boolean changed = false;
        for (JsonNode message : messages) {
            if (!isAssistant(message)) {
                continue;
            }
            JsonNode reasoning = message.get(REASONING_CONTENT);
            if (reasoning == null || !reasoning.isTextual()) {
                // A missing field and a non-string one are both «nothing to pass back» to the provider.
                ((ObjectNode) message).put(REASONING_CONTENT, "");
                changed = true;
            }
        }
        return changed ? JsonUtils.MAPPER.writeValueAsBytes(root) : null;
    }

    /** Whether any assistant turn in the request carries a non-empty reasoning — the dialect's proof. */
    private static boolean carriesReasoning(JsonNode messages) {
        for (JsonNode message : messages) {
            JsonNode reasoning = message.get(REASONING_CONTENT);
            if (isAssistant(message) && reasoning != null && reasoning.isTextual()
                    && !reasoning.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAssistant(JsonNode message) {
        return ASSISTANT.equals(message.path("role").asText());
    }
}
