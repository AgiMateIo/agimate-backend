package ru.agimate.userapi.service.mail;

import jakarta.annotation.PostConstruct;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import ru.agimate.userapi.config.MailProperties;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The one way a letter leaves this service. Plain SMTP and nothing else: an installation points it
 * at its own mailbox, at a corporate relay or at a sending service — all three speak the same
 * protocol, so which one it is stops being an architectural decision and becomes a line in the
 * environment.
 *
 * <p>Sending is off until {@code spring.mail.host} is configured, and off is a legitimate state: a
 * self-hosted installation that never set up mail still starts, and the flows that need a letter
 * simply are not offered.
 */
@Slf4j
@Component
public class MailService {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final MailProperties properties;
    private final MailTemplates templates;
    private final String host;

    public MailService(ObjectProvider<JavaMailSender> mailSender, MailProperties properties,
                       MailTemplates templates, @Value("${spring.mail.host:}") String host) {
        this.mailSender = mailSender;
        this.properties = properties;
        this.templates = templates;
        this.host = host;
    }

    public boolean isConfigured() {
        return StringUtils.hasText(host) && mailSender.getIfAvailable() != null;
    }

    /**
     * Asynchronous, and that is part of the contract: SMTP answers in its own time, and an HTTP
     * request must not be held for it. The consequence is that a failure to send is visible in the
     * log and nowhere else — which is why every flow that sends a letter also offers to send it
     * again.
     */
    @Async("mailExecutor")
    public void send(String to, String template, Map<String, String> variables) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || !StringUtils.hasText(host)) {
            log.warn("mail is not configured — the {} letter to {} was not sent", template, masked(to));
            return;
        }

        try {
            MailTemplates.Letter letter = templates.render(template, variables);

            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            helper.setTo(to);
            helper.setFrom(properties.getFrom(), StringUtils.hasText(properties.getFromName())
                    ? properties.getFromName()
                    : properties.getFrom());
            helper.setSubject(letter.subject());
            helper.setText(letter.html(), true);

            sender.send(message);
            log.debug("sent the {} letter to {}", template, masked(to));
        } catch (Exception e) {
            log.warn("the {} letter to {} was not sent: {}", template, masked(to), e.toString());
        }
    }

    /**
     * Startup is the only moment a misconfiguration can be reported to whoever is deploying, so the
     * two states worth distinguishing are said out loud here: off, or on and to where.
     */
    @PostConstruct
    void reportConfiguration() {
        if (!StringUtils.hasText(host)) {
            log.info("Mail sending is off: spring.mail.host is not set");
            return;
        }
        if (!StringUtils.hasText(properties.getFrom())) {
            throw new IllegalStateException("app.mail.from is empty while spring.mail.host is set "
                    + "(env APP_MAIL_FROM) — a letter with no sender is refused by every relay");
        }
        log.info("Mail sending through {} as {}", host, properties.getFrom());
    }

    /** Addresses are personal data and letters are logged on every send. The domain is enough to debug. */
    private static String masked(String address) {
        int at = address.indexOf('@');
        return at <= 0 ? "***" : address.charAt(0) + "***" + address.substring(at);
    }
}
