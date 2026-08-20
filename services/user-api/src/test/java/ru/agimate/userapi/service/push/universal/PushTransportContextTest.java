package ru.agimate.userapi.service.push.universal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.service.push.PushTransport;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Транспорты как бины, а не как объекты: остальные тесты собирают их руками и потому не видят
 * ничего из того, чем занимается Spring. Второй конструктор — хоть бы и ради теста — контейнер
 * выбирать не станет и потребует конструктор без аргументов, и узнаётся это только на старте.
 */
@DisplayName("транспорты в контексте")
class PushTransportContextTest {

    private static final ApplicationContextRunner CONTEXT = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(RuStorePushTransport.class, FirebasePushTransport.class));

    @Test
    @DisplayName("без кред контекст поднимается, транспорта два, отправка выключена")
    void bothTransportsAreBeans() {
        CONTEXT.withBean(PushProperties.class, PushProperties::new).run(context -> {
            assertNull(context.getStartupFailure());

            Map<String, PushTransport> transports = context.getBeansOfType(PushTransport.class);
            assertEquals(2, transports.size());
            assertTrue(transports.values().stream().noneMatch(PushTransport::isConfigured));
        });
    }

    /** Отказ старта на мусорных кредах — обещание, которое проверяется только целым контекстом. */
    @Test
    @DisplayName("нечитаемые креды FCM роняют старт")
    void unreadableCredentialsFailTheStartup() {
        PushProperties properties = new PushProperties();
        properties.getFcm().setCredentials("это не JSON");

        CONTEXT.withBean(PushProperties.class, () -> properties).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(messages(context.getStartupFailure()).contains("app.push.fcm.credentials"));
        });
    }

    /** Половина кред RuStore — та же история, только проверка живёт в @PostConstruct. */
    @Test
    @DisplayName("половина кред RuStore роняет старт")
    void halfConfiguredRuStoreFailsTheStartup() {
        PushProperties properties = new PushProperties();
        properties.getRustore().setProjectId("project-1");

        CONTEXT.withBean(PushProperties.class, () -> properties).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(messages(context.getStartupFailure()).contains("app.push.rustore"));
        });
    }

    private static String messages(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            chain.append(cause.getMessage()).append('\n');
        }
        return chain.toString();
    }
}
