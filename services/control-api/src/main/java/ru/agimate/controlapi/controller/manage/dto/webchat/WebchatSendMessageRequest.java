package ru.agimate.controlapi.controller.manage.dto.webchat;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Отправка сообщения в webchat-сессию. {@code text} и {@code parts} по отдельности опциональны, но
 * хотя бы одно должно быть непустым (проверяется в сервисе). Каждый элемент {@code parts} несёт
 * {@code {"fileId": "agf_…"}} — файл, ранее загруженный через {@code POST /manage/webchat/files}.
 */
@Schema(description = "Send a message into a webchat session")
public record WebchatSendMessageRequest(
        @Schema(description = "Message text (optional when parts are present)")
        String text,

        @Schema(description = "Attachments: [{\"fileId\": \"agf_…\"}] from POST /manage/webchat/files")
        List<Map<String, Object>> parts
) {
}
