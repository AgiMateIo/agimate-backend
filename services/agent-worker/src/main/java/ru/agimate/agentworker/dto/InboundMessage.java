package ru.agimate.agentworker.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The user's message, already extracted by control-api. Phase 1 fills only {@code text}
 * (generic connectors fall back to raw JSON on control-api, not here); {@code parts} is
 * reserved for media.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundMessage(String text, List<Object> parts) {
}
