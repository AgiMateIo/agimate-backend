package ru.agimate.agentworker.workers;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.agimate.agentworker.dto.AgentMessage;

/**
 * A steering signal delivered to an active run's {@code control} mailbox. {@code STEER} carries a
 * new message to fold into the running conversation; {@code INTERRUPT} asks it to stop gracefully.
 * Sent/received as a JSON string over the DBOS mailbox so its reconstruction is independent of the
 * transport's serialization strategy.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlSignal(String type, AgentMessage message) {

    public static final String STEER = "steer";
    public static final String INTERRUPT = "interrupt";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ControlSignal steer(AgentMessage message) {
        return new ControlSignal(STEER, message);
    }

    public static ControlSignal interrupt() {
        return new ControlSignal(INTERRUPT, null);
    }

    public boolean isInterrupt() {
        return INTERRUPT.equals(type);
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize control signal", e);
        }
    }

    public static ControlSignal fromJson(String json) {
        try {
            return MAPPER.readValue(json, ControlSignal.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse control signal", e);
        }
    }
}
