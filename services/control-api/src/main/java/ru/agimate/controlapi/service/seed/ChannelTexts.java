package ru.agimate.controlapi.service.seed;

import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.ContentProperties;

import java.util.Properties;

/**
 * The platform's own words to a user in a channel: {@code seed/texts/<lang>/channel.properties}.
 *
 * <p>A third bundle next to {@link ConnectorTexts} and {@link PromptTexts} because the reader is a
 * third one again. The connector catalogue is read by a human in the UI, the prompt blocks by the
 * model — and this is read by a human in the middle of a conversation, where the surrounding text was
 * written by the agent. Hence the rule these texts live by: they must be short and unmistakably
 * clerical, so nobody takes them for the agent speaking. They also never reach the agent's history,
 * because backend-sent messages are not written into {@code channel_session_messages}.
 */
@Component
public class ChannelTexts {

    /** Answer to a stop command when there was no live run to stop. */
    public static final String NOTHING_TO_STOP = "channel.nothing-to-stop";

    private final Properties texts;

    public ChannelTexts(ContentProperties contentProperties) {
        this.texts = SeedTextBundle.load(contentProperties.getLanguage(), "channel.properties");
    }

    /** A text by key; no translation — the fallback from the code, as everywhere in seed texts. */
    public String get(String key, String fallback) {
        return texts.getProperty(key, fallback);
    }
}
