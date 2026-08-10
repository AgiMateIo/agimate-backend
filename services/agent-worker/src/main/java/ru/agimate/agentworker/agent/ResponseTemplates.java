package ru.agimate.agentworker.agent;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.config.AgentProperties;

import java.util.Locale;

/**
 * User-facing response notices, resolved once for the deployment's configured language
 * ({@code agent.response.language}) from {@code messages*.properties} via Spring's
 * {@link MessageSource}. Per-deployment for now; a per-agent locale sourced from the run is the
 * eventual axis. Only end-user notices live here — model-facing prompt text is not localized here.
 */
@Component
public class ResponseTemplates {

    private final MessageSource messages;
    private final Locale locale;

    public ResponseTemplates(MessageSource messages, AgentProperties props) {
        this.messages = messages;
        this.locale = Locale.forLanguageTag(props.getResponse().getLanguage());
    }

    public String maxTurns() {
        return get("notice.max-turns");
    }

    public String modelError() {
        return get("notice.model-error");
    }

    public String authError() {
        return get("notice.auth-error");
    }

    /** The agent has no usable chat model — an owner-actionable notice, not a «model error». */
    public String noModel() {
        return get("notice.no-model");
    }

    /** The model produced no text at all — a provider-side degenerate finish, not a content problem. */
    public String emptyAnswer() {
        return get("notice.empty-answer");
    }

    /** The run was stopped by the user — an ordinary ending, so it goes out as the answer, not as an error. */
    public String cancelled() {
        return get("notice.cancelled");
    }

    /** Lead-in for the receipt: what the agent had managed to do before the stop. */
    public String cancelledDidRun() {
        return get("notice.cancelled-did-run");
    }

    public String truncated() {
        return get("notice.truncated");
    }

    public String filtered() {
        return get("notice.filtered");
    }

    public String infraError() {
        return get("notice.infra-error");
    }

    private String get(String key) {
        return messages.getMessage(key, null, locale);
    }
}
