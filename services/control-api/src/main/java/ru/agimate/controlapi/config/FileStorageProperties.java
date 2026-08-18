package ru.agimate.controlapi.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The connectors' file layer (docs/connectors/files.md): an S3-compatible backend plus limits.
 * Credentials ({@code access-key}/{@code secret-key}) are never put into yaml — env only
 * ({@code APP_FILES_ACCESS_KEY}/{@code APP_FILES_SECRET_KEY}); when neither is set, the standard AWS
 * credentials chain is used.
 */
@Component
@ConfigurationProperties(prefix = "app.files")
@Getter
@Setter
public class FileStorageProperties {

    /** Blob backend: {@code local} (disk, the default — for development and single-node) or {@code s3}. */
    private String backend = "local";
    /** Root of the local backend; empty — {@code ~/.agimate/files}. */
    private String localDir;

    private String bucket = "agimate-files";
    private String region = "us-east-1";
    /** S3-compatible endpoint (MinIO and the like); empty — AWS. */
    private String endpoint;
    private String accessKey;
    private String secretKey;
    /** Path-style bucket addressing (required by MinIO). */
    private boolean pathStyle = true;
    /**
     * Presigned links straight into the object store instead of streaming the bytes through
     * control-api (docs/connectors/files.md). Opt-in: it needs an endpoint the browser can reach plus
     * CORS on the bucket, and it weakens revocation — a link outlives deletion of the file until the
     * blob is swept.
     */
    private boolean presign = false;
    /**
     * The endpoint presigned links point at, when the browser cannot reach {@code endpoint} itself
     * (MinIO on a cluster-internal address); empty — {@code endpoint}.
     */
    private String publicEndpoint;

    /** Maximum size of a single file (the ceiling of a Telegram bot upload — 50 MB). */
    private long maxFileSizeBytes = 50L * 1024 * 1024;
    /** Daily byte quota per user (a rolling 24-hour window). */
    private long userDailyBytes = 500L * 1024 * 1024;
    /** Default TTL when the producer did not set its own. */
    private Duration defaultTtl = Duration.ofDays(7);

    /**
     * HMAC secret of signed links ({@code GET /files/…?exp&sig}); env only
     * ({@code APP_FILES_URL_SECRET}), and mandatory outside the dev profiles ({@code SecurityGuardConfig}).
     */
    private String urlSecret;
    /** Lifetime of a signed link; chat history issues fresh links on every read. */
    private Duration urlTtl = Duration.ofMinutes(15);
}
