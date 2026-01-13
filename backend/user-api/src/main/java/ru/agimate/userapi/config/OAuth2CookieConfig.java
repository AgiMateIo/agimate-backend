package ru.agimate.userapi.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.agimate.common.util.CryptoUtils;

import javax.crypto.SecretKey;

/**
 * Configuration for OAuth2 cookie-based authorization request storage.
 *
 * Provides encryption key for OAuth2 cookies that can be shared across multiple backend instances.
 */
@Configuration
@Slf4j
public class OAuth2CookieConfig {

    @Value("${app.oauth.cookie-encryption-key}")
    private String cookieEncryptionKeyBase64;

    /**
     * Creates a SecretKey from Base64-encoded key in configuration.
     * This ensures all backend instances use the same encryption key for OAuth2 cookies.
     */
    @Bean
    public SecretKey oauth2CookieEncryptionKey() {
        SecretKey key = CryptoUtils.keyFromBase64(cookieEncryptionKeyBase64);
        log.info("Loaded OAuth2 cookie encryption key from configuration (AES-256)");
        return key;
    }
}
