package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Файловый слой коннекторов (docs/connectors/files.md): S3-совместимый backend + лимиты.
 * Креды ({@code access-key}/{@code secret-key}) в yaml не кладутся — только env
 * ({@code APP_FILES_ACCESS_KEY}/{@code APP_FILES_SECRET_KEY}); если не заданы обе — используется
 * стандартная AWS credentials chain.
 */
@Component
@ConfigurationProperties(prefix = "app.files")
@Getter
@Setter
public class FileStorageProperties {

    private String bucket = "agimate-files";
    private String region = "us-east-1";
    /** S3-совместимый endpoint (MinIO и т.п.); пусто — AWS. */
    private String endpoint;
    private String accessKey;
    private String secretKey;
    /** Path-style адресация бакета (требуется MinIO). */
    private boolean pathStyle = true;

    /** Максимальный размер одного файла (потолок Telegram-бот-аплоада — 50 MB). */
    private long maxFileSizeBytes = 50L * 1024 * 1024;
    /** Суточная квота байтов на пользователя (скользящее окно 24 ч). */
    private long userDailyBytes = 500L * 1024 * 1024;
    /** TTL по умолчанию, когда продюсер не задал свой. */
    private Duration defaultTtl = Duration.ofDays(7);
}
