package ru.agimate.controlapi.service.channel.handler.dto;

import java.util.Map;

/**
 * Вложение мультимодального сообщения (изображение, аудио, файл).
 *
 * <p>Фаза 1 (текст) поле parts не использует; модель введена сразу, чтобы контракт
 * {@link InboundMessage}/{@link OutboundMessage} не пришлось менять при добавлении медиа.
 *
 * @param type       тип вложения (например {@code "image"}, {@code "audio"}, {@code "file"})
 * @param storageRef ссылка на содержимое в объектном хранилище
 * @param mime       MIME-тип
 * @param size       размер в байтах
 * @param meta       произвольные метаданные (имя файла, длительность, транскрипция ...)
 */
public record Part(
        String type,
        String storageRef,
        String mime,
        long size,
        Map<String, Object> meta
) {

    /** Тип вложения по MIME: {@code image|video|audio|file} — как рендерить/подавать в LLM. */
    public static String typeForMime(String mime) {
        if (mime == null) {
            return "file";
        }
        if (mime.startsWith("image/")) {
            return "image";
        }
        if (mime.startsWith("video/")) {
            return "video";
        }
        if (mime.startsWith("audio/")) {
            return "audio";
        }
        return "file";
    }
}
