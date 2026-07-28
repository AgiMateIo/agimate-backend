package ru.agimate.agentworker.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A reference to an inbound attachment inside an {@link AgentChatMessage} — metadata and the
 * {@code agf_} id only, with no bytes. The bytes are pulled at LLM-call time ({@code GetFile}, like
 * api_key — outside the DBOS checkpoint), so the ref is safe both in a step's checkpoint and in the
 * LLM workflow's input.
 *
 * @param fileId {@code agf_<uuid>}
 * @param type   image | video | audio | file — the worker feeds only image to the model as Media
 * @param mime   MIME of the contents
 * @param size   size in bytes
 * @param name   file name when known; otherwise empty
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FilePartRef(String fileId, String type, String mime, long size, String name) {

    public boolean isImage() {
        return "image".equals(type);
    }
}
