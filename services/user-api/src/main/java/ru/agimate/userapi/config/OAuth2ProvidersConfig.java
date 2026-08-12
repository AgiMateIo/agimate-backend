package ru.agimate.userapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import ru.agimate.userapi.security.oauth2.providers.OAuthUserAdapters;
import ru.agimate.userapi.security.oauth2.providers.VkAwareUserRequestEntityConverter;
import ru.agimate.userapi.security.oauth2.providers.VkUserAdapter;

import java.util.UUID;

/**
 * Where the login flow bends around a provider. VK ID is the reason all three beans exist: it is
 * OAuth 2.1 with its own additions, while Google, Yandex and GitHub go through Spring's defaults.
 */
@Configuration
public class OAuth2ProvidersConfig {

    private static final String DEVICE_ID_PARAMETER = "device_id";

    @Bean
    public OAuth2UserService<OAuth2UserRequest, OAuth2User> oAuth2UserService(OAuthUserAdapters adapters) {
        DefaultOAuth2UserService userService = new DefaultOAuth2UserService();
        userService.setRequestEntityConverter(new VkAwareUserRequestEntityConverter());
        userService.setAttributesConverter(userRequest -> attributes ->
                adapters.require(userRequest.getClientRegistration().getRegistrationId())
                        .normalize(userRequest, attributes));
        return userService;
    }

    /**
     * VK ID returns a {@code device_id} on the callback and demands it back when the code is
     * exchanged. It is not part of an OAuth2 authorization response, so Spring drops it on the way —
     * the servlet request is the only place it still exists at that moment.
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> accessTokenResponseClient() {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.addParametersConverter(grantRequest -> {
            if (!VkUserAdapter.REGISTRATION_ID.equals(grantRequest.getClientRegistration().getRegistrationId())) {
                return null;
            }
            MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
            parameters.add(DEVICE_ID_PARAMETER, requireDeviceId());
            return parameters;
        });
        return client;
    }

    /**
     * VK ID rejects the padded Base64 state Spring generates by default, so VK gets a plain
     * 32-character hex one. PKCE needs no help here: the registration authenticates as a public
     * client, and the resolver applies the challenge on its own for those.
     */
    @Bean
    public OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository clientRegistrationRepository) {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository,
                OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(builder -> builder.attributes(attributes -> {
            if (VkUserAdapter.REGISTRATION_ID.equals(attributes.get(OAuth2ParameterNames.REGISTRATION_ID))) {
                builder.state(UUID.randomUUID().toString().replace("-", ""));
            }
        }));
        return resolver;
    }

    private static String requireDeviceId() {
        String deviceId = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest().getParameter(DEVICE_ID_PARAMETER)
                : null;
        if (!StringUtils.hasText(deviceId)) {
            throw new OAuth2AuthorizationException(new OAuth2Error("invalid_request",
                    "VK ID did not return a device_id on the callback", null));
        }
        return deviceId;
    }
}
