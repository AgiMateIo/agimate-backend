package ru.agimate.controlapi.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import ru.agimate.controlapi.config.FileStorageProperties;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SignedFileUrlService")
class SignedFileUrlServiceTest {

    private static final Pattern URL_PATTERN =
            Pattern.compile("^/files/(agf_[0-9a-f-]+)\\?exp=(\\d+)&sig=([A-Za-z0-9_-]+)$");

    private final String fileId = FileIds.external(UUID.randomUUID());

    private FileStorageProperties props;
    private SignedFileUrlService service;

    @BeforeEach
    void setUp() {
        props = new FileStorageProperties();
        props.setUrlSecret("test-secret");
        props.setUrlTtl(Duration.ofMinutes(15));
        service = newService(props);
    }

    private static SignedFileUrlService newService(FileStorageProperties props) {
        SignedFileUrlService created = new SignedFileUrlService(props);
        created.initKey();
        return created;
    }

    private Matcher issued() {
        Matcher matcher = URL_PATTERN.matcher(service.issue(fileId));
        assertTrue(matcher.matches());
        return matcher;
    }

    @Nested
    @DisplayName("issue")
    class Issue {

        @Test
        @DisplayName("относительный URL /files/{id}?exp&sig с exp ≈ now + url-ttl")
        void urlShape() {
            Matcher matcher = issued();
            assertEquals(fileId, matcher.group(1));
            long exp = Long.parseLong(matcher.group(2));
            long expected = Instant.now().plus(props.getUrlTtl()).getEpochSecond();
            assertTrue(Math.abs(exp - expected) <= 5);
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
            assertFalse(newService(other).verify(
                    fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
        }

        @Test
        @DisplayName("пустой секрет — случайный per-boot ключ, свои ссылки валидны")
        void blankSecretFallsBackToRandomKey() {
            SignedFileUrlService devService = newService(new FileStorageProperties());
            Matcher matcher = URL_PATTERN.matcher(devService.issue(fileId));
            assertTrue(matcher.matches());
            assertTrue(devService.verify(fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
            // Другой инстанс (другой boot) те же ссылки не признаёт.
            assertFalse(newService(new FileStorageProperties())
                    .verify(fileId, Long.parseLong(matcher.group(2)), matcher.group(3)));
        }
    }
}
