package ru.agimate.userapi.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/oauth2")
@RequiredArgsConstructor
@Slf4j
public class OAuthController {

    private final ClientRegistrationRepository clientRegistrationRepository;


    @GetMapping("/error")
    public ResponseEntity<String> handleOAuthError(@RequestParam(required = false) String error) {
        log.error("OAuth2 authentication error: {}", error);
        return ResponseEntity.badRequest().body("OAuth2 authentication failed: " + error);
    }
}