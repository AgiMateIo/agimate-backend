package ru.agimate.userapi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Which login providers this installation actually offers.
 *
 * <p>Every provider is declared in {@code application.yaml} with everything that is the same
 * everywhere — endpoints, scopes, the redirect template — while the credentials come from the
 * environment alone ({@code SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_<ID>_CLIENT_ID}). A provider
 * whose credentials are not set is simply switched off.
 */
// Boot binds the properties as part of its own OAuth2 client auto-configuration, which steps aside
// as soon as this class declares a ClientRegistrationRepository — so the binding is asked for here.
@Configuration
@EnableConfigurationProperties(OAuth2ClientProperties.class)
@Slf4j
public class OAuth2RegistrationsConfig {

    /**
     * Drops the providers left unconfigured. Without this the declaration alone is enough to keep
     * the service from starting: {@link OAuth2ClientProperties} validates itself on initialization
     * and rejects a blank client id — which is why the config used to carry fake ids just to boot.
     */
    @Bean
    static BeanPostProcessor unconfiguredOAuth2RegistrationsRemover() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof OAuth2ClientProperties properties) {
                    properties.getRegistration().entrySet()
                            .removeIf(entry -> !StringUtils.hasText(entry.getValue().getClientId()));
                }
                return bean;
            }
        };
    }

    /**
     * Replaces the auto-configured repository for one reason: an installation may offer no login
     * provider at all (a developer working on something else), and the stock one refuses an empty
     * set. Deliberately not {@code Iterable} — the generated Spring Security login page is not used
     * here, the frontend draws its own buttons.
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(OAuth2ClientProperties properties) {
        Map<String, ClientRegistration> registrations =
                Map.copyOf(new OAuth2ClientPropertiesMapper(properties).asClientRegistrations());
        log.info("OAuth2 login providers: {}", registrations.isEmpty() ? "none configured" : registrations.keySet());
        return registrations::get;
    }
}
