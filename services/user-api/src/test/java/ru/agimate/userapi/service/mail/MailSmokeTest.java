package ru.agimate.userapi.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import ru.agimate.userapi.config.MailProperties;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends one real letter through a real relay. Disabled by default — this is the check that the
 * configuration works, and it needs something on the other end of the socket.
 * <p>
 * Against the mailpit of {@code docker compose --profile infra}, where the letter then shows up at
 * <a href="http://localhost:8025">localhost:8025</a>:
 * <pre>
 * ./gradlew :user-api:test --tests "*MailSmokeTest" -Dmail.smoke=true
 * </pre>
 * Against a real mailbox, which is what tells you whether the domain's SPF and DKIM are in order:
 * <pre>
 * ./gradlew :user-api:test --tests "*MailSmokeTest" -Dmail.smoke=true \
 *   -Dmail.smoke.host=smtp.yandex.ru -Dmail.smoke.port=465 -Dmail.smoke.ssl=true \
 *   -Dmail.smoke.username=no-reply@agimate.ru -Dmail.smoke.password=… \
 *   -Dmail.smoke.from=no-reply@agimate.ru -Dmail.smoke.to=…
 * </pre>
 */
class MailSmokeTest {

    @Test
    @DisplayName("send one letter through the configured relay")
    @EnabledIfSystemProperty(named = "mail.smoke", matches = "true")
    void send() {
        String host = System.getProperty("mail.smoke.host", "localhost");
        int port = Integer.parseInt(System.getProperty("mail.smoke.port", "1025"));
        String from = System.getProperty("mail.smoke.from", "no-reply@agimate.lc");
        String to = System.getProperty("mail.smoke.to", "someone@example.org");

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(System.getProperty("mail.smoke.username", ""));
        sender.setPassword(System.getProperty("mail.smoke.password", ""));
        if (!sender.getUsername().isBlank()) {
            sender.getJavaMailProperties().put("mail.smtp.auth", "true");
        }
        if (Boolean.getBoolean("mail.smoke.ssl")) {
            sender.getJavaMailProperties().put("mail.smtp.ssl.enable", "true");
        }

        MailProperties properties = new MailProperties();
        properties.setFrom(from);
        properties.setFromName("AgiMate");

        MailService mailService = new MailService(
                provider(sender), properties, new MailTemplates("ru"), host);

        assertTrue(mailService.isConfigured());
        // Called directly, so @Async does not apply and the send has finished by the time the
        // assertion below runs. Failures are logged rather than thrown — read the log, then mailpit.
        mailService.send(to, "password-reset", Map.of(
                "name", "Eugene",
                "link", "https://www.agimate.ru/password/reset?token=smoke",
                "hours", "1"));

        System.out.println();
        System.out.println("=== Mail smoke ===");
        System.out.println("Sent " + from + " -> " + to + " through " + host + ":" + port);
        if ("localhost".equals(host)) {
            System.out.println("Read it at http://localhost:8025");
        }
        System.out.println("==================");
    }

    /** {@code ObjectProvider} has no public constructor; the smallest bean factory is how you get one. */
    private static org.springframework.beans.factory.ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("javaMailSender", sender);
        return beanFactory.getBeanProvider(JavaMailSender.class);
    }
}
