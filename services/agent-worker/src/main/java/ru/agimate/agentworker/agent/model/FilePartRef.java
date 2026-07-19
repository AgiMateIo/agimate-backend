package ru.agimate.agentworker.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ссылка на inbound-вложение внутри {@link AgentChatMessage} — только метаданные и {@code agf_}-id,
 * без байтов. Байты подтягиваются в момент LLM-вызова ({@code GetFile}, как api_key — вне DBOS-
 * чекпоинта), поэтому ref безопасно попадает и в чекпоинт шага, и во вход LLM-воркфлоу.
 *
 * @param fileId {@code agf_<uuid>}
 * @param type   image | video | audio | file — только image воркер подаёт в модель как Media
 * @param mime   MIME содержимого
 * @param size   размер в байтах
 * @param name   имя файла, если известно; иначе пусто
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FilePartRef(String fileId, String type, String mime, long size, String name) {

    public boolean isImage() {
        return "image".equals(type);
    }
}
