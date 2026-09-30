package ru.agimate.controlapi.connectors.internal.web;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Operator settings of the web connector (docs/connectors/web.md). The search key is the platform's:
 * users have no key of their own, which is why searches are capped per user.
 */
@Component
@ConfigurationProperties(prefix = "app.connectors.web")
@Getter
@Setter
public class WebProperties {

    private Search search = new Search();
    private Yandex yandex = new Yandex();

    @Getter
    @Setter
    public static class Search {

        /** Searches per user over a sliding 24 hours; {@code 0} — no limit. */
        private int dailyLimitPerUser = 100;
    }

    @Getter
    @Setter
    public static class Yandex {

        /** Empty — search is not configured on this installation. */
        private String apiKey = "";
        private String folderId = "";

        public boolean configured() {
            // An empty value in yaml (api-key:) binds as null, not as "".
            return StringUtils.hasText(apiKey) && StringUtils.hasText(folderId);
        }
    }
}
