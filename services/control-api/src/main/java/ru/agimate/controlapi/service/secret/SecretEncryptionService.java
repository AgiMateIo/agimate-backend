package ru.agimate.controlapi.service.secret;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.agimate.common.util.CryptoUtils;
import ru.agimate.controlapi.database.entities.Secret;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/**
 * Envelope-шифрование секретов. KEK (master key) — один источник (config
 * {@code app.secrets.encryption-key}, base64 32 байта; в проде задаётся env'ом через relaxed
 * binding). На каждый секрет генерится случайный DEK:
 * <ul>
 *   <li>{@code encrypted_data} = AES-256-GCM(plaintext, DEK, iv);</li>
 *   <li>{@code encrypted_dek} = [IV(12)] + AES-256-GCM(DEK, KEK, AAD = entity + ownerId).</li>
 * </ul>
 * AAD-привязка к владельцу: DEK не расшифровать, перенеся строку {@code secrets} на другого
 * владельца (другой {@code entity}/{@code ownerId}). {@code ownerId} в строке не хранится.
 */
@Service
public class SecretEncryptionService {

    private final SecretKey kek;

    public SecretEncryptionService(@Value("${app.secrets.encryption-key}") String kekBase64) {
        this.kek = CryptoUtils.keyFromBase64(kekBase64);
    }

    /**
     * Зашифровать plaintext под нового владельца. Возвращает НЕсохранённую сущность {@link Secret}
     * (без id) — сохранение на вызывающем.
     */
    public Secret encrypt(String entity, UUID ownerId, byte[] plaintext) {
        SecretKey dek = CryptoUtils.generateAES256Key();

        byte[] dataIv = CryptoUtils.randomBytes(CryptoUtils.gcmIvLength());
        byte[] encData = CryptoUtils.encryptGcm(plaintext, dek, dataIv, null);

        byte[] dekIv = CryptoUtils.randomBytes(CryptoUtils.gcmIvLength());
        byte[] encDek = CryptoUtils.encryptGcm(dek.getEncoded(), kek, dekIv, aad(entity, ownerId));

        return Secret.builder()
                .entity(entity)
                .iv(b64(dataIv))
                .encryptedData(b64(encData))
                .encryptedDek(b64(concat(dekIv, encDek)))
                .build();
    }

    /** Расшифровать полезные данные секрета. Бросает, если AAD (entity+ownerId) не совпадает. */
    public byte[] decrypt(Secret secret, UUID ownerId) {
        byte[] dekBlob = unb64(secret.getEncryptedDek());
        int ivLen = CryptoUtils.gcmIvLength();
        byte[] dekIv = Arrays.copyOfRange(dekBlob, 0, ivLen);
        byte[] encDek = Arrays.copyOfRange(dekBlob, ivLen, dekBlob.length);
        byte[] dekBytes = CryptoUtils.decryptGcm(encDek, kek, dekIv, aad(secret.getEntity(), ownerId));
        SecretKey dek = CryptoUtils.keyFromBytes(dekBytes);

        return CryptoUtils.decryptGcm(unb64(secret.getEncryptedData()), dek, unb64(secret.getIv()), null);
    }

    /** Перешифровать существующую строку на новый plaintext (in-place, тот же владелец). */
    public void reencrypt(Secret secret, UUID ownerId, byte[] plaintext) {
        Secret fresh = encrypt(secret.getEntity(), ownerId, plaintext);
        secret.setIv(fresh.getIv());
        secret.setEncryptedData(fresh.getEncryptedData());
        secret.setEncryptedDek(fresh.getEncryptedDek());
    }

    private static byte[] aad(String entity, UUID ownerId) {
        return (entity + ":" + ownerId).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] unb64(String s) {
        return Base64.getDecoder().decode(s);
    }
}
