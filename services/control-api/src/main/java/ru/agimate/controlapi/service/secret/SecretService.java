package ru.agimate.controlapi.service.secret;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.agimate.common.util.JsonUtils;
import ru.agimate.controlapi.database.entities.Secret;
import ru.agimate.controlapi.database.repositories.SecretRepository;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Storage and reading of credential secrets (a map of {@code field code → value}) on top of
 * {@link SecretEncryptionService} plus {@link SecretRepository}. {@code ownerId} is the id of the
 * owning entity (e.g. {@code connection.id}); it is used as the AAD binding and is not stored in the
 * row.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecretService {

    /** The map key for single values ({@link #storeValue}/{@link #revealValue}). */
    private static final String VALUE_KEY = "value";

    private final SecretRepository secretRepository;
    private final SecretEncryptionService encryptionService;

    /** Encrypt and store a credentials map; return the stored row. */
    @Transactional
    public Secret store(String entity, UUID ownerId, Map<String, String> credentials) {
        Secret secret = encryptionService.encrypt(entity, ownerId, toBytes(credentials));
        return secretRepository.save(secret);
    }

    /** Re-encrypt an existing row onto a new credentials map. */
    @Transactional
    public Secret update(Secret secret, UUID ownerId, Map<String, String> credentials) {
        encryptionService.reencrypt(secret, ownerId, toBytes(credentials));
        return secretRepository.save(secret);
    }

    /** Decrypt a credentials map. Throws when the AAD (entity+ownerId) does not match. */
    public Map<String, String> reveal(Secret secret, UUID ownerId) {
        byte[] plaintext = encryptionService.decrypt(secret, ownerId);
        return JsonUtils.readValue(new String(plaintext, StandardCharsets.UTF_8),
                JsonUtils.MAP_STRING_TYPE_REFERENCE);
    }

    /** A single value (webhook secrets and the like) — the same map with one key. */
    @Transactional
    public Secret storeValue(String entity, UUID ownerId, String value) {
        return store(entity, ownerId, Map.of(VALUE_KEY, value));
    }

    @Transactional
    public Secret updateValue(Secret secret, UUID ownerId, String value) {
        return update(secret, ownerId, Map.of(VALUE_KEY, value));
    }

    public String revealValue(Secret secret, UUID ownerId) {
        return reveal(secret, ownerId).get(VALUE_KEY);
    }

    private static byte[] toBytes(Map<String, String> credentials) {
        return JsonUtils.writeValueAsString(credentials).getBytes(StandardCharsets.UTF_8);
    }
}
