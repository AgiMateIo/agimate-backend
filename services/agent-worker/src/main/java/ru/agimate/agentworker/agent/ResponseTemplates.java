package ru.agimate.agentworker.agent;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import ru.agimate.agentworker.config.AgentProperties;

import java.util.Locale;

/**
 * Response notices, resolved once for the deployment's configured language
 * ({@code agent.response.language}) from {@code messages*.properties} via Spring's
 * {@link MessageSource}. Per-deployment for now; a per-agent locale sourced from the run is the
 * eventual axis.
 *
 * <p>End-user notices ({@code notice.*}) plus the texts the model reads ({@code prompt.*}). The
 * latter follow the dialogue's language, not the deployment's, so this is the right bundle for them
 * only while the two coincide.
 */
@Component
public class ResponseTemplates {

    private final MessageSource messageSource;
    private final Locale locale;

    public ResponseTemplates(MessageSource messageSource, AgentProperties props) {
        this.messageSource = messageSource;
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

    /** The soft landing's «finish with what you have» — read by the model, never shown to the user. */
    public String wrapUp() {
        return get("prompt.wrap-up");
    }

    /** Pins the block {@code tag} below as data, ahead of an untrusted prompt block. */
    public String untrustedPreamble(String tag) {
        return get("prompt.untrusted-preamble", tag);
    }

    /** System paragraph: output wrapped in {@code tag} is third-party data, not commands. */
    public String toolOutputGuidance(String tag) {
        return get("prompt.tool-output-guidance", tag);
    }

    /** System paragraph: a detached tool returns a task handle the model must neither re-invoke nor invent. */
    public String detachedToolGuidance() {
        return get("prompt.detached-tool-guidance");
    }

    /** Frames a message absorbed mid-run: it arrived while the model was working, not with the request. */
    public String steeredPrefix() {
        return get("prompt.steered-prefix");
    }

    /** System hint for a call carrying image attachments the model sees inline. */
    public String imageVisible() {
        return get("prompt.image-visible");
    }

    /** System hint for a call carrying image attachments a text-only model cannot see. */
    public String imageNotVisible() {
        return get("prompt.image-not-visible");
    }

    private String get(String key, Object... args) {
        return messageSource.getMessage(key, args.length == 0 ? null : args, locale);
    }
}
