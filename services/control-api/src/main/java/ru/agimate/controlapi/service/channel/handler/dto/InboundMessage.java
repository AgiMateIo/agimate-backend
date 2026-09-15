package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.List;

/**
 * The unified incoming message — the result of {@link ChannelHandler#handleInput}, handed to the
 * worker.
 *
 * <p>The handler reduces heterogeneous triggers (text, audio, photo) to this shape. In Phase 1 only
 * {@code text} is populated; {@code parts} is reserved for media. The answer's address stays in
 * control-api, in the run's channel snapshot ({@link ChannelHandler#replyAddress}), so it is absent here.
 *
 * @param text  the message's text (for media — a transcript or a caption)
 * @param parts attachments (Phase 1: empty)
 */
public record InboundMessage(
        String text,
        List<Part> parts
) {

    public static InboundMessage text(String text) {
        return new InboundMessage(text, List.of());
    }
}
