package ru.agimate.controlapi.storage;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.FileStorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * S3-compatible {@link BlobStore} (AWS S3 / MinIO / cloud.ru), enabled by {@code app.files.backend=s3}.
 * The client is created lazily on first use — control-api starts even with no storage configured, and a
 * configuration error surfaces on the first real use rather than at bootRun.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.files", name = "backend", havingValue = "s3")
@RequiredArgsConstructor
public class S3BlobStore implements BlobStore {

    private final FileStorageProperties props;

    private volatile S3Client client;
    private volatile S3Presigner presigner;
    /** Set once a presigner cannot be built: without it every row of a listing would retry and log. */
    private volatile boolean presignUnavailable;

    @Override
    public void put(String key, InputStream content, long contentLength, String mime) {
        try {
            client().putObject(PutObjectRequest.builder()
                            .bucket(props.getBucket())
                            .key(key)
                            .contentType(mime)
                            .contentLength(contentLength)
                            .build(),
                    RequestBody.fromInputStream(content, contentLength));
        } catch (SdkException e) {
            throw new FileStorageException("blob store put failed: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return client().getObject(GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
        } catch (NoSuchKeyException e) {
            throw new FileStorageException("blob not found: " + key, e);
        } catch (SdkException e) {
            throw new FileStorageException("blob store get failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            // S3 DeleteObject is idempotent: a missing key is a success.
            client().deleteObject(DeleteObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
        } catch (SdkException e) {
            throw new FileStorageException("blob store delete failed: " + e.getMessage(), e);
        }
    }

    /**
     * Presigned GET (SigV4 in the query string). The response header overrides are signed along with
     * the rest, so a storage that does not implement them still serves the object — with the headers
     * it was stored with. Any failure degrades to {@code empty}: the caller then streams the bytes,
     * which is what happens without presigning anyway.
     */
    @Override
    public Optional<URI> presignGet(String key, Duration ttl, ResponseHeaders headers) {
        if (!props.isPresign() || presignUnavailable) {
            return Optional.empty();
        }
        try {
            GetObjectRequest.Builder get = GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    // The link is a capability with a short life, and so are the bytes behind it: no
                    // shared cache may outlive it. On our own path this is CacheControl of the response.
                    .responseCacheControl("private, max-age=" + ttl.toSeconds());
            if (headers != null) {
                get.responseContentType(headers.contentType())
                        .responseContentDisposition(headers.contentDisposition());
            }
            return Optional.of(presigner().presignGetObject(GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(get.build())
                            .build())
                    .url()
                    .toURI());
        } catch (Exception e) {
            log.warn("presigning {} failed, falling back to streaming: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private S3Client client() {
        S3Client c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    client = c = buildClient();
                }
            }
        }
        return c;
    }

    /**
     * Unlike the client, a presigner that cannot be built disables presigning for good instead of
     * being retried: the caller has a working fallback, and retrying would repeat the failure once per
     * row of a listing.
     */
    private S3Presigner presigner() {
        S3Presigner p = presigner;
        if (p == null) {
            synchronized (this) {
                p = presigner;
                if (p == null) {
                    try {
                        presigner = p = buildPresigner();
                    } catch (RuntimeException e) {
                        presignUnavailable = true;
                        throw e;
                    }
                }
            }
        }
        return p;
    }

    private S3Client buildClient() {
        log.info("building S3 client: endpoint={} bucket={} pathStyle={}",
                props.getEndpoint(), props.getBucket(), props.isPathStyle());
        var builder = S3Client.builder()
                .region(Region.of(props.getRegion()))
                .forcePathStyle(props.isPathStyle());
        if (notBlank(props.getEndpoint())) {
            builder.endpointOverride(URI.create(props.getEndpoint()));
        }
        AwsCredentialsProvider credentials = staticCredentials();
        if (credentials != null) {
            builder.credentialsProvider(credentials);
        }
        return builder.build();
    }

    /**
     * The presigner signs URLs for the browser, so it is built on the public endpoint: the address
     * control-api itself uses may be cluster-internal (MinIO in a private network), and a link to it
     * would be unreachable from outside.
     */
    private S3Presigner buildPresigner() {
        String endpoint = notBlank(props.getPublicEndpoint()) ? props.getPublicEndpoint() : props.getEndpoint();
        log.info("building S3 presigner: endpoint={} ttl={}", endpoint, props.getUrlTtl());
        var builder = S3Presigner.builder()
                .region(Region.of(props.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyle())
                        .build());
        if (notBlank(endpoint)) {
            builder.endpointOverride(URI.create(endpoint));
        }
        AwsCredentialsProvider credentials = staticCredentials();
        if (credentials != null) {
            builder.credentialsProvider(credentials);
        }
        return builder.build();
    }

    /** @return null when no keys are configured — the SDK then falls back to its own credentials chain */
    private AwsCredentialsProvider staticCredentials() {
        if (notBlank(props.getAccessKey()) && notBlank(props.getSecretKey())) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey()));
        }
        return null;
    }

    /** Both hold connection pools and, without static keys, a credentials provider with its own threads. */
    @PreDestroy
    void shutdown() {
        if (client != null) {
            client.close();
        }
        if (presigner != null) {
            presigner.close();
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
