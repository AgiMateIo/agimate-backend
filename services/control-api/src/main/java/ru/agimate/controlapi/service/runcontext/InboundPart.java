package ru.agimate.controlapi.service.runcontext;

/**
 * A reference to an inbound attachment within a run's context — the wire form for
 * {@code RunContext.inbound_parts}. Metadata and an {@code agf_} reference only; the worker pulls the
 * bytes with a separate {@code GetFile}, so only the reference enters the
 * {@code prepare_context} DBOS checkpoint (see docs/connectors/files.md).
 *
 * @param fileId the file's public id ({@code agf_<uuid>})
 * @param type   attachment type (image | video | audio | file) — it drives the worker's multimodality
 * @param mime   MIME of the contents
 * @param size   size in bytes
 * @param name   file name when known (a Telegram document); otherwise empty
 */
public record InboundPart(String fileId, String type, String mime, long size, String name) {
}
