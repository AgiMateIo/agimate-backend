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
 * Envelope encryption of secrets. The KEK (master key) has a single source (the config
 * {@code app.secrets.encryption-key}, base64 of 32 bytes; in production it is set from env through
 * relaxed binding). A random DEK is generated per secret:
 * <ul>
 *   <li>{@code encrypted_data} = AES-256-GCM(plaintext, DEK, iv);</li>
 *   <li>{@code encrypted_dek} = [IV(12)] + AES-256-GCM(DEK, KEK, AAD = entity + ownerId).</li>
 * </ul>
 * The AAD binds the secret to its owner: the DEK cannot be decrypted after moving a {@code secrets} row
 * to a different owner (a different {@code entity}/{@code ownerId}). {@code ownerId} is not stored in
 * the row.
 */
@Service
public class SecretEncryptionService {

    private final SecretKey kek;

    public SecretEncryptionService(@Value("${app.secrets.encryption-key}") String kekBase64) {
        if (kekBase64 == null || kekBase64.isBlank()) {
            throw new IllegalStateException("app.secrets.encryption-key (env APP_SECRETS_ENCRYPTION_KEY) "
                    + "is not set; generate with: openssl rand -base64 32");
        }
        this.kek = CryptoUtils.keyFromBase64(kekBase64);
    }

    /**
     * Encrypt a plaintext for a new owner. Returns an UNSAVED {@link Secret} entity (with no id) —
     * saving is the caller's job.
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

    /** Decrypt a secret's payload. Throws when the AAD (entity+ownerId) does not match. */
    public byte[] decrypt(Secret secret, UUID ownerId) {
        byte[] dekBlob = unb64(secret.getEncryptedDek());
        int ivLen = CryptoUtils.gcmIvLength();
        byte[] dekIv = Arrays.copyOfRange(dekBlob, 0, ivLen);
        byte[] encDek = Arrays.copyOfRange(dekBlob, ivLen, dekBlob.length);
        byte[] dekBytes = CryptoUtils.decryptGcm(encDek, kek, dekIv, aad(secret.getEntity(), ownerId));
        SecretKey dek = CryptoUtils.keyFromBytes(dekBytes);

        return CryptoUtils.decryptGcm(unb64(secret.getEncryptedData()), dek, unb64(secret.getIv()), null);
    }

    /** Re-encrypt an existing row onto a new plaintext (in place, same owner). */
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
