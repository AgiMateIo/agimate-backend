package ru.agimate.controlapi.service.channel.handler.dto;

import ru.agimate.controlapi.service.channel.handler.ChannelHandler;

import java.util.List;

/**
 * The unified answer from the model — content for {@link ChannelHandler#handleOutput} (the mirror of
 * {@link InboundMessage}). Content only: addressing and correlation are carried by
 * {@link OutboundDispatch}.
 *
 * <p>The handler expands it into a call of the right connector tool. In Phase 1 only {@code text} is
 * used; {@code parts} is reserved for media answers.
 *
 * @param text  the answer's text
 * @param parts attachments (Phase 1: empty)
 */
public record OutboundMessage(
        String text,
        List<Part> parts
) {

    public static OutboundMessage text(String text) {
        return new OutboundMessage(text, List.of());
    }
}
