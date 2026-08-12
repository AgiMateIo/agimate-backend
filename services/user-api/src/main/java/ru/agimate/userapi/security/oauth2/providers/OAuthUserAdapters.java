package ru.agimate.userapi.security.oauth2.providers;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Lookup of {@link OAuthUserAdapter} by the registration id Spring reports on the callback. */
@Component
public class OAuthUserAdapters {

    private final Map<String, OAuthUserAdapter> byRegistrationId;

    public OAuthUserAdapters(List<OAuthUserAdapter> adapters) {
        this.byRegistrationId = adapters.stream()
                .collect(Collectors.toMap(OAuthUserAdapter::registrationId, Function.identity()));
    }

    public OAuthUserAdapter require(String registrationId) {
        OAuthUserAdapter adapter = byRegistrationId.get(registrationId.toLowerCase());
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported OAuth provider: " + registrationId);
        }
        return adapter;
    }
}
