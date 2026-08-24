package ru.agimate.controlapi.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.config.FileStorageProperties;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SignedFileUrlService")
class SignedFileUrlServiceTest {

    private static final Pattern URL_PATTERN =
            Pattern.compile("^/files/(agf_[0-9a-f-]+)\\?exp=(\\d+)&sig=([A-Za-z0-9_-]+)$");

    private static final UUID USER_ID = UUID.randomUUID();

    private final String fileId = FileIds.external(UUID.randomUUID());
    private final FileLink link = new FileLink(USER_ID, fileId, "image/png", "скриншот.png");

    private FileStorageProperties props;
    private StubBlobStore blobStore;
    private SignedFileUrlService service;

    @BeforeEach
    void setUp() {
        props = new FileStorageProperties();
        props.setUrlSecret("test-secret");
        props.setUrlTtl(Duration.ofMinutes(15));
        blobStore = new StubBlobStore();
        service = newService(props, blobStore);
    }

    private static SignedFileUrlService newService(FileStorageProperties props, BlobStore blobStore) {
        SignedFileUrlService created = new SignedFileUrlService(props, blobStore);
        created.initKey();
        return created;
    }

    private Matcher issued() {
        Matcher matcher = URL_PATTERN.matcher(service.issue(link));
        assertTrue(matcher.matches());
        return matcher;
    }

    /** A blob store that signs only what it was told to; put/get/delete are never touched here. */
    private static class StubBlobStore implements BlobStore {

        private URI presigned;
        private String key;
        private Duration ttl;
        private ResponseHeaders headers;

        @Override
        public void put(String key, InputStream content, long contentLength, ResponseHeaders headers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<URI> presignGet(String key, Duration ttl, ResponseHeaders headers) {
            this.key = key;
            this.ttl = ttl;
            this.headers = headers;
            return Optional.ofNullable(presigned);
        }
    }

    @Nested
    @DisplayName("issue")
    class Issue {

        @Test
        @DisplayName("хранилище не подписывает — относительный /files/{id}?exp&sig с exp ≈ now + url-ttl")
        void urlShape() {
            Matcher matcher = issued();
            assertEquals(fileId, matcher.group(1));
            long exp = Long.parseLong(matcher.group(2));
            long expected = Instant.now().plus(props.getUrlTtl()).getEpochSecond();
            assertTrue(Math.abs(exp - expected) <= 5);
        }

        @Test
        @DisplayName("подписанная хранилищем ссылка отдаётся как есть — байты минуют control-api")
        void presignedWins() {
            blobStore.presigned = URI.create("https://s3.cloud.ru/bucket/" + USER_ID + "/" + fileId
                    + "?X-Amz-Signature=abc");

            assertEquals(blobStore.presigned.toString(), service.issue(link));
            assertEquals(USER_ID + "/" + fileId, blobStore.key);
            assertEquals(props.getUrlTtl(), blobStore.ttl);
        }

        @Test
        @DisplayName("прямой ссылке передаётся то же представление, что отдал бы control-api")
        void presignCarriesResponseHeaders() {
            blobStore.presigned = URI.create("https://s3.cloud.ru/bucket/key?X-Amz-Signature=abc");

            assertEquals(blobStore.presigned.toString(), service.issue(link));
            assertEquals("image/png", blobStore.headers.contentType());
            assertTrue(blobStore.headers.contentDisposition().startsWith("inline;"));

            assertEquals(blobStore.presigned.toString(),
                    service.issue(new FileLink(USER_ID, fileId, "image/svg+xml", "картинка.svg")));
            // Active content degrades to octet-stream on the direct path too.
            assertEquals("application/octet-stream", blobStore.headers.contentType());
            assertTrue(blobStore.headers.contentDisposition().startsWith("attachment;"));
        }

        @Test
        @DisplayName("часть без mime не подписывается — представление угадывать нельзя")
        void unknownMimeIsNotPresigned() {
            blobStore.presigned = URI.create("https://s3.cloud.ru/bucket/key?X-Amz-Signature=abc");

            assertTrue(URL_PATTERN.matcher(service.issue(new FileLink(USER_ID, fileId, null, null)))
                    .matches());
            assertNull(blobStore.key);
        }
    }

    @Nested
    @DisplayName("verify")
    class Verify {

        @Test
        @DisplayName("выданная ссылка валидна")
        void roundTrip() {
            Matcher matcher = issued();
            assertTrue(service.verify(fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
        }

        @Test
        @DisplayName("истёкший exp отклоняется даже с честной подписью на него")
        void expiredRejected() {
            props.setUrlTtl(Duration.ofSeconds(-60));
            Matcher matcher = issued();
            assertFalse(service.verify(fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
        }

        @Test
        @DisplayName("подмена exp или fileId ломает подпись")
        void tamperedRejected() {
            Matcher matcher = issued();
            long exp = Long.parseLong(matcher.group(2));
            String sig = matcher.group(3);
            assertFalse(service.verify(fileId, exp + 1000, sig));
            assertFalse(service.verify(FileIds.external(UUID.randomUUID()), exp, sig));
            assertFalse(service.verify(fileId, exp, "not-a-signature"));
        }

        @Test
        @DisplayName("подпись зависит от секрета")
        void differentSecretRejected() {
            Matcher matcher = issued();
            FileStorageProperties other = new FileStorageProperties();
            other.setUrlSecret("another-secret");
            assertFalse(newService(other, new StubBlobStore()).verify(
                    fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
        }

        @Test
        @DisplayName("пустой секрет — случайный per-boot ключ, свои ссылки валидны")
        void blankSecretFallsBackToRandomKey() {
            SignedFileUrlService devService = newService(new FileStorageProperties(), new StubBlobStore());
            Matcher matcher = URL_PATTERN.matcher(devService.issue(link));
            assertTrue(matcher.matches());
            assertTrue(devService.verify(fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
            // Другой инстанс (другой boot) те же ссылки не признаёт.
            assertFalse(newService(new FileStorageProperties(), new StubBlobStore())
                    .verify(fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
        }
    }
}
