package ru.agimate.userapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Who the letters come from. The switch that decides whether letters are sent at all is not here but
 * in {@code spring.mail.host}: an installation that needs no mail configures nothing, exactly as it
 * does with an OAuth provider it does not offer. A flag of our own would add the one state that
 * takes hours to diagnose — switched on with nowhere to send through.
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
@Getter
@Setter
public class MailProperties {

    /** The From address. Empty while a host is configured is a typo — {@code MailService} refuses to start. */
    private String from = "";

    /** The name shown beside the address; falls back to the address itself when blank. */
    private String fromName = "";
}
