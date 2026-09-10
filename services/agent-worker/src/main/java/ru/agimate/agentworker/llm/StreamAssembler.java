package ru.agimate.agentworker.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import ru.agimate.agentworker.agent.model.AgentChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Collects a streamed chat response into the single turn the loop expects — the same shape
 * {@link LlmMessageMapper#fromResponse} builds from a whole response, so nothing above the
 * transport can tell which way the answer arrived.
 *
 * <p>Stateful and single-threaded by construction: one instance per streaming attempt, fed from the
 * subscriber. Text arrives as deltas and is concatenated here. Reasoning does not: Spring AI's
 * {@code ChunkMerger} folds {@code reasoning_content} into every chunk that follows, so each chunk
 * carries the reasoning so far and the last one carries all of it — appending the chunks would
 * repeat it quadratically. Tool calls arrive whole, because the same merger buffers a tool-call
 * sequence and merges its deltas before emitting — so nothing here re-implements that merge either.
 *
 * <p>Everything it holds is the answer being written, and it holds only that: no chunk is retained
 * past {@link #accept}, so what stays in memory is the size of the turn, not of the stream.
 */
public final class StreamAssembler {

    private final LlmMessageMapper mapper;

    private final long startedAt = System.nanoTime();
    private int chunks;
    private long firstChunkAt;

    private final StringBuilder text = new StringBuilder();
    private String reasoning;
    private final List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
    private String finishReason;
    private Usage usage;

    public StreamAssembler(LlmMessageMapper mapper) {
        this.mapper = mapper;
    }

    /** Fold one chunk in. Chunks carrying nothing but metadata (the usage-only tail) are normal. */
    public void accept(ChatResponse chunk) {
        if (chunks++ == 0) {
            firstChunkAt = System.nanoTime();
        }
        Generation result = chunk.getResult();
        if (result != null && result.getOutput() != null) {
            AssistantMessage delta = result.getOutput();
            if (delta.getText() != null) {
                text.append(delta.getText());
            }
            String thought = mapper.reasoning(chunk);
            if (thought != null) {
                // The running total, not a delta (see the class javadoc): the latest chunk supersedes.
                reasoning = thought;
            }
            for (AssistantMessage.ToolCall call : delta.getToolCalls()) {
                // A merged tool-call chunk repeats nothing, but a stray nameless delta would produce a
                // call the backend cannot dispatch — the name is what the tool is addressed by.
                if (call.name() != null && !call.name().isBlank()) {
                    toolCalls.add(call);
                }
            }
        }
        String reason = mapper.finishReason(chunk);
        if (reason != null && !reason.isBlank()) {
            finishReason = reason;
        }
        Usage counted = chunk.getMetadata() != null ? chunk.getMetadata().getUsage() : null;
        if (counted != null && counted.getTotalTokens() != null && counted.getTotalTokens() > 0) {
            // Last non-empty wins: with include_usage the counts ride the tail chunk, but a gateway
            // that repeats a running total on every chunk must not leave us with the first one.
            usage = counted;
        }
    }

    /**
     * Has any of the answer arrived yet? The line a broken stream is judged by: before it nothing
     * has been produced and the attempt can be repeated, after it the tokens are spent and a repeat
     * would buy them again.
     */
    public boolean started() {
        return !text.isEmpty() || reasoning != null || !toolCalls.isEmpty();
    }

    /** The provider's {@code finish_reason}, or {@code null} if the stream ended without one. */
    public String finishReason() {
        return finishReason;
    }

    /** Token counts from the tail chunk, or {@code null} when the provider sent none. */
    public Usage usage() {
        return usage;
    }

    /** Elements received so far, metadata-only ones included. */
    public int chunks() {
        return chunks;
    }

    /**
     * What has arrived, for the log: how many chunks and how long the first took, and how much of the
     * turn they add up to. The first-chunk figure is the number the waiting budgets are tuned by —
     * it is the model's thinking time on a provider that streams, and the whole generation on one
     * that buffers.
     */
    public String summary() {
        String first = chunks == 0 ? "none"
                : "first after " + TimeUnit.NANOSECONDS.toMillis(firstChunkAt - startedAt) + " ms";
        return chunks + " chunks (" + first + "), " + text.length() + " chars text, "
                + (reasoning == null ? 0 : reasoning.length()) + " chars reasoning, "
                + toolCalls.size() + " tool calls";
    }

    /**
     * The assembled turn.
     *
     * @param callId the LLM call's id — the seed tool call ids are minted from, exactly as on the
     *               non-streaming path, because the backend keys tool call idempotency on them
     */
    public AgentChatMessage message(String callId) {
        List<AgentChatMessage.ToolCall> calls = new ArrayList<>(toolCalls.size());
        for (AssistantMessage.ToolCall call : toolCalls) {
            calls.add(new AgentChatMessage.ToolCall(
                    LlmMessageMapper.mintToolCallId(callId, calls.size()), call.name(), call.arguments()));
        }
        return AgentChatMessage.assistant(text.toString(), reasoning, calls);
    }
}
