package ru.agimate.userapi.service.push.universal;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.agimate.userapi.config.PushProperties;
import ru.agimate.userapi.database.entities.PushProvider;
import ru.agimate.userapi.service.push.PushMessage;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Про выписку самого access-токена теста здесь нет: она ходит в сеть. Проверяется то, что решается
 * на старте (читаемость кред и project_id, взятый из них же) и то, что токен спрашивается заново
 * на каждой отправке.
 */
@DisplayName("FirebasePushTransport")
class FirebasePushTransportTest {

    private static String privateKeyPem;

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        PrivateKey key = generator.generateKeyPair().getPrivate();
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static PushProperties properties(String credentials) {
        PushProperties properties = new PushProperties();
        properties.getFcm().setCredentials(credentials);
        return properties;
    }

    private static String serviceAccount(String projectId) {
        String json = ("{\"type\": \"service_account\","
                + "\"project_id\": \"%s\","
                + "\"private_key_id\": \"key-1\","
                + "\"private_key\": \"%s\","
                + "\"client_email\": \"push@%s.iam.gserviceaccount.com\","
                + "\"client_id\": \"100000000000000000000\","
                + "\"token_uri\": \"https://oauth2.googleapis.com/token\"}")
                .formatted(projectId, privateKeyPem.replace("\n", "\\n"), projectId);
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("пустые креды — канал выключен, старт проходит")
    void emptyCredentialsDisableSending() {
        FirebasePushTransport transport = assertDoesNotThrow(() -> new FirebasePushTransport(properties("")));

        assertFalse(transport.isConfigured());
        assertEquals(PushProvider.FIREBASE, transport.provider());
    }

    /** Пустые креды — решение, всё остальное нечитаемое — опечатка в выкладке. */
    @Test
    @DisplayName("нечитаемые креды — отказ старта")
    void unreadableCredentialsFailFast() {
        assertThrows(IllegalStateException.class, () -> new FirebasePushTransport(properties("не base64 и не JSON")));
        assertThrows(IllegalStateException.class, () -> new FirebasePushTransport(
                properties(Base64.getEncoder().encodeToString("{\"type\": \"authorized_user\"}".getBytes(StandardCharsets.UTF_8)))));
    }

    /** Отдельной настройки с проектом нет, поэтому её отсутствие в JSON — тоже отказ старта. */
    @Test
    @DisplayName("в JSON нет project_id — отказ старта")
    void serviceAccountWithoutProjectFailsFast() {
        String withoutProject = Base64.getEncoder().encodeToString(("{\"type\": \"service_account\","
                + "\"private_key_id\": \"key-1\","
                + "\"private_key\": \"%s\","
                + "\"client_email\": \"push@example.iam.gserviceaccount.com\","
                + "\"client_id\": \"100000000000000000000\"}")
                .formatted(privateKeyPem.replace("\n", "\\n")).getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class, () -> new FirebasePushTransport(properties(withoutProject)));
    }

    /**
     * Токен живёт около часа, поэтому взятый один раз транспорт проработал бы час после деплоя и
     * замолчал — отказ отложенный и в логах ничем не примечательный. Единственное, чем это
     * стеречь без сети: спрашивать креды на каждой отправке, а не в конструкторе.
     */
    @Test
    @DisplayName("токен спрашивается заново на каждой отправке")
    void tokenIsRefreshedPerSend() throws Exception {
        GoogleCredentials credentials = mock(GoogleCredentials.class);
        when(credentials.getAccessToken()).thenReturn(new AccessToken("ya29.first", new Date()));
        FirebasePushTransport transport = new FirebasePushTransport(new PushProperties(), credentials, "agimate");
        PushMessage message = new PushMessage(Map.of("type", "webchat_message"), null);

        transport.body("token-1", message);
        when(credentials.getAccessToken()).thenReturn(new AccessToken("ya29.second", new Date()));
        Map<String, Object> second = transport.body("token-2", message);

        verify(credentials, times(2)).refreshIfExpired();
        @SuppressWarnings("unchecked")
        Map<String, Object> fcm = (Map<String, Object>) ((Map<String, Object>) second.get("providers")).get("fcm");
        assertEquals("ya29.second", fcm.get("auth_token"));
    }

    /**
     * Имя канала в теле запроса — не то, под которым лежит токен: SDK на устройстве называет его
     * {@code firebase}, API отправки — {@code fcm}. Расхождение вендорское, и стеречь его больше
     * нечем.
     */
    @Test
    @DisplayName("проект берётся из JSON, а в тело запроса канал едет как fcm")
    void projectComesFromTheServiceAccount() {
        FirebasePushTransport transport = new FirebasePushTransport(properties(serviceAccount("agimate")));

        assertTrue(transport.isConfigured());
        assertEquals("agimate", transport.projectId());
        assertEquals("fcm", transport.wireName());
        assertEquals(PushProvider.FIREBASE, transport.provider());
    }
}
