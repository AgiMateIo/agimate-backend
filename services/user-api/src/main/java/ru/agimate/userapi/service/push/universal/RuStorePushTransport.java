package ru.agimate.userapi.service.push.universal;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;

/**
 * RuStore's own channel of the universal sending service ({@link UniversalPushTransport}). Its
 * credentials are a pair of constants from the RuStore console — the project the application is
 * built against and a service key that does not expire — so there is nothing to keep alive here.
 */
@Slf4j
@Component
public class RuStorePushTransport extends UniversalPushTransport {

    public RuStorePushTransport(PushProperties pushProperties) {
        super(pushProperties);
    }

    /**
     * Half-filled credentials fail the boot. Empty ones are a decision — this installation does not
     * send — while one of the two is a typo, and the only place it would otherwise surface is a
     * notification that never arrives.
     */
    @PostConstruct
    void checkConfiguration() {
        PushProperties.RuStore rustore = pushProperties.getRustore();
        if (rustore.isHalfConfigured()) {
            throw new IllegalStateException(
                    "app.push.rustore: project-id and service-key must be set together — one of them is empty");
        }
        if (!rustore.isConfigured()) {
            log.info("RuStore push is not configured — subscriptions are stored, notifications are not sent");
        }
    }

    @Override
    public PushProvider provider() {
        return PushProvider.RUSTORE;
    }

    @Override
    public boolean isConfigured() {
        return pushProperties.getRustore().isConfigured();
    }

    @Override
    protected String wireName() {
        return "rustore";
    }

    @Override
    protected String projectId() {
        return pushProperties.getRustore().getProjectId();
    }

    @Override
    protected String authToken() {
        return pushProperties.getRustore().getServiceKey();
    }
}
