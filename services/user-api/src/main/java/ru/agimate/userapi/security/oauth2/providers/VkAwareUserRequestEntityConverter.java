package ru.agimate.userapi.security.oauth2.providers;

import org.springframework.http.RequestEntity;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequestEntityConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * VK ID expects {@code client_id} next to the access token on the user-info call, which the standard
 * form-post request does not carry. Other providers ignore the parameter, so the copy is only made
 * for VK to keep their requests byte-for-byte as Spring builds them.
 */
public class VkAwareUserRequestEntityConverter extends OAuth2UserRequestEntityConverter {

    @Override
    @SuppressWarnings("unchecked")
    public RequestEntity<?> convert(OAuth2UserRequest userRequest) {
        RequestEntity<?> request = super.convert(userRequest);
        if (request == null
                || !VkUserAdapter.REGISTRATION_ID.equals(userRequest.getClientRegistration().getRegistrationId())
                || !(request.getBody() instanceof MultiValueMap<?, ?> body)) {
            return request;
        }

        MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>((MultiValueMap<String, String>) body);
        parameters.set("client_id", userRequest.getClientRegistration().getClientId());
        return new RequestEntity<>(parameters, request.getHeaders(), request.getMethod(), request.getUrl());
    }
}
