package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

import java.util.Properties;

/**
 * Localisation of the trusted instructions the platform puts into an agent's prompt:
 * {@code seed/<lang>/prompt.properties}.
 *
 * <p>These are not captions in an interface but <b>behaviour</b>: the rules of autonomous event
 * handling, the ban on imitating a tool call as text, the attach convention, the instructions for
 * reacting to a connector's event. The model understands Russian too, but it follows an instruction
 * more reliably when it is in the language of the rest of the prompt — and in a RU installation the
 * agent's instructions and its skills are Russian.
 *
 * <p>That is exactly why this is a separate bundle from {@link ConnectorTexts}: the connector
 * catalogue's reader is a human and the cost of an error is an ugly caption, while here the reader is
 * the model and the cost of an error is different agent behaviour. Such texts cannot be handed out for
 * translation under the same rules.
 *
 * <p>Keys: {@code run.trigger.guidance}, {@code run.tool-call.guidance},
 * {@code run.attachment.guidance} — platform-level, applied to every matching run;
 * {@code connector.<code>.<trigger>.guidance} falling back to {@code connector.<code>.guidance} — the
 * instruction for reacting to a particular connector's event.
 */
@Component
public class PromptTexts {

    /** Rules of autonomous event handling — trigger runs. */
    public static final String RUN_TRIGGER_GUIDANCE = "run.trigger.guidance";
    /** The ban on imitating a tool call as text — runs that have tools. */
    public static final String RUN_TOOL_CALL_GUIDANCE = "run.tool-call.guidance";
    /** The attach convention — DIALOGUE runs whose prompt channel supports attachments. */
    public static final String RUN_ATTACHMENT_GUIDANCE = "run.attachment.guidance";

    private final Properties texts;

    public PromptTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "prompt.properties");
    }

    /** A platform prompt block by key; no translation — the value from the code. */
    public String get(String key, String fallback) {
        return texts.getProperty(key, fallback);
    }

    /**
     * The instruction for reacting to a connector's event ({@code ContextDirectives.guidance}). First
     * the key carrying the trigger's name, then the connector's general one — so a connector with one
     * instruction covering several events (board) keeps it under a single key, and the translations do
     * not drift apart between copies.
     */
    public String triggerGuidance(String connectorCode, String triggerName, String fallback) {
        String specific = texts.getProperty("connector.%s.%s.guidance".formatted(connectorCode, triggerName));
        if (specific != null) {
            return specific;
        }
        return texts.getProperty("connector.%s.guidance".formatted(connectorCode), fallback);
    }
}
