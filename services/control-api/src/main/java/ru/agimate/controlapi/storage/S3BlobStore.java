package ru.agimate.controlapi.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import ru.agimate.controlapi.config.FileStorageProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.net.URI;

/**
 * S3-compatible {@link BlobStore} (AWS S3 / MinIO), enabled by {@code app.files.backend=s3}. The
 * client is created lazily on first use — control-api starts even with no storage configured, and a
 * configuration error surfaces on the first real use rather than at bootRun.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.files", name = "backend", havingValue = "s3")
@RequiredArgsConstructor
public class S3BlobStore implements BlobStore {

    private final FileStorageProperties props;

    private volatile S3Client client;

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

    private S3Client buildClient() {
        log.info("building S3 client: endpoint={} bucket={} pathStyle={}",
                props.getEndpoint(), props.getBucket(), props.isPathStyle());
        var builder = S3Client.builder()
                .region(Region.of(props.getRegion()))
                .forcePathStyle(props.isPathStyle());
        if (props.getEndpoint() != null && !props.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(props.getEndpoint()));
        }
        if (notBlank(props.getAccessKey()) && notBlank(props.getSecretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())));
        }
        return builder.build();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
