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
 * Хранение/чтение секретов-credential'ов (мапа {@code код поля → значение}) поверх
 * {@link SecretEncryptionService} + {@link SecretRepository}. {@code ownerId} — id сущности-владельца
 * (например {@code connection.id}); используется как AAD-привязка, в строке не хранится.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SecretService {

    /** Ключ мапы для одиночных значений ({@link #storeValue}/{@link #revealValue}). */
    private static final String VALUE_KEY = "value";

    private final SecretRepository secretRepository;
    private final SecretEncryptionService encryptionService;

    /** Зашифровать и сохранить мапу credentials; вернуть сохранённую строку. */
    @Transactional
    public Secret store(String entity, UUID ownerId, Map<String, String> credentials) {
        Secret secret = encryptionService.encrypt(entity, ownerId, toBytes(credentials));
        return secretRepository.save(secret);
    }

    /** Перешифровать существующую строку на новую мапу credentials. */
    @Transactional
    public Secret update(Secret secret, UUID ownerId, Map<String, String> credentials) {
        encryptionService.reencrypt(secret, ownerId, toBytes(credentials));
        return secretRepository.save(secret);
    }

    /** Расшифровать мапу credentials. Бросает, если AAD (entity+ownerId) не совпадает. */
    public Map<String, String> reveal(Secret secret, UUID ownerId) {
        byte[] plaintext = encryptionService.decrypt(secret, ownerId);
        return JsonUtils.readValue(new String(plaintext, StandardCharsets.UTF_8),
                JsonUtils.MAP_STRING_TYPE_REFERENCE);
    }

    /** Одиночное значение (webhook-секреты и т.п.) — та же мапа с единственным ключом. */
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
