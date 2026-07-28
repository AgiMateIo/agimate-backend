package ru.agimate.controlapi.service.runcontext;

import java.util.List;

/**
 * The full context of a run for {@code GetRunContext}: ordered prompt blocks (stable ones first — the
 * prompt cache), scoped tools, the session's history «as the user saw it» (completed runs only, with
 * the window and the filter applied on the backend) and the dialogue inbound's attachments
 * ({@code inboundParts} — references only, the worker pulls the bytes with {@code GetFile}). The
 * worker renders it as-is.
 */
public record RunContextView(
        List<RunBlock> systemBlocks,
        List<RunBlock> userBlocks,
        List<RunTool> tools,
        List<RunHistoryMessage> history,
        List<InboundPart> inboundParts
) {
}
