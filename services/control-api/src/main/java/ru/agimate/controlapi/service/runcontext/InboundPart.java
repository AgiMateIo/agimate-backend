package ru.agimate.controlapi.service.runcontext;

/**
 * Ссылка на inbound-вложение в составе контекста рана — wire-форма для {@code RunContext.inbound_parts}.
 * Только метаданные и {@code agf_}-ссылка; байты воркер тянет отдельным {@code GetFile}, поэтому в
 * DBOS-чекпоинт {@code prepare_context} попадает лишь ссылка (см. docs/connectors/files.md).
 *
 * @param fileId публичный id файла ({@code agf_<uuid>})
 * @param type   тип вложения (image | video | audio | file) — ведёт мультимодальность воркера
 * @param mime   MIME содержимого
 * @param size   размер в байтах
 * @param name   имя файла, если известно (Telegram document); иначе пусто
 */
public record InboundPart(String fileId, String type, String mime, long size, String name) {
}
