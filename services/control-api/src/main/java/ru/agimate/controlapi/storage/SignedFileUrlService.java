package ru.agimate.controlapi.storage;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.FileStorageProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Подписанные ссылки на файлы для браузера (docs/connectors/files.md): {@code Authorization}-header
 * в {@code <img src>} не подставить, поэтому доступ авторизуется capability-ссылкой —
 * HMAC-SHA256 по {@code fileId|exp} с коротким TTL. Владение проверяется в момент выдачи ссылки
 * (parts отдаются только владельцу webchat-сессии); сама ссылка до истечения {@code exp}
 * эквивалентна праву чтения одного файла.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SignedFileUrlService {

    public static final String PATH_PREFIX = "/files/";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final FileStorageProperties props;

    private SecretKeySpec key;

    @PostConstruct
    void initKey() {
        String secret = props.getUrlSecret();
        if (secret == null || secret.isBlank()) {
            // Вне dev-профилей сюда не дойти: секрет обязателен (SecurityGuardConfig, fail-fast).
            // В dev случайный per-boot ключ даёт рабочие ссылки без настройки; после рестарта
            // выданные ссылки протухают — фронт перечитывает историю и получает свежие.
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            key = new SecretKeySpec(random, HMAC_ALGORITHM);
            log.warn("app.files.url-secret is not set - using a random per-boot key (dev only)");
            return;
        }
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /** Относительный подписанный URL ({@code /files/agf_…?exp=…&sig=…}); origin добавляет фронт. */
    public String issue(String fileId) {
        long exp = Instant.now().plus(props.getUrlTtl()).getEpochSecond();
        return PATH_PREFIX + fileId + "?exp=" + exp + "&sig=" + sign(fileId, exp);
    }

    /** Валидна ли подпись и не истёк ли срок; причины отказа намеренно неразличимы для клиента. */
    public boolean verify(String fileId, long exp, String sig) {
        if (sig == null || Instant.now().getEpochSecond() > exp) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(fileId, exp).getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String fileId, long exp) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((fileId + "|" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
