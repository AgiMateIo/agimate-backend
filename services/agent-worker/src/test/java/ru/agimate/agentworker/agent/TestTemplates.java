package ru.agimate.agentworker.agent;

import org.springframework.context.support.ResourceBundleMessageSource;
import ru.agimate.agentworker.config.AgentProperties;

/** The real bundles for a given language, wired the way application.yaml does it. */
public final class TestTemplates {

    private TestTemplates() {
    }

    public static ResponseTemplates of(String lang) {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        AgentProperties props = new AgentProperties();
        props.getResponse().setLanguage(lang);
        return new ResponseTemplates(ms, props);
    }
}
