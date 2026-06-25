package ru.agimate.controlapi.database.entities;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import ru.agimate.common.persistence.BaseEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Зашифрованный секрет (envelope-шифрование). Общий стор обратимых секретов платформы:
 * outbound-credentials коннекторов ({@code entity = connection}), API-ключи LLM-провайдеров
 * ({@code entity = llm_provider}) и т.п.
 *
 * <p>Схема: случайный per-row DEK шифрует {@code encrypted_data} (AES-256-GCM, IV = {@code iv});
 * сам DEK шифруется KEK (один источник, см. {@code SecretEncryptionService}) и лежит в
 * {@code encrypted_dek}. KEK при шифровании DEK подмешивает AAD = {@code entity + owner_id} —
 * строку нельзя расшифровать, перенеся на другого владельца. {@code owner_id} в таблице НЕ хранится:
 * это привязка, которую передаёт вызывающий (владелец знает свой id).
 */
@Entity
@Table(name = "secrets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Secret extends BaseEntity {

    @Id
    @Generated
    @ColumnDefault("uuidv7()")
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Имя сущности-владельца (часть AAD): например {@code connection}, {@code llm_provider}. */
    @Column(name = "entity", nullable = false, columnDefinition = "TEXT")
    private String entity;

    /** DEK, зашифрованный KEK (формат [IV(12)][ciphertext+tag]), base64. */
    @Column(name = "encrypted_dek", nullable = false, columnDefinition = "TEXT")
    private String encryptedDek;

    /** IV шифра данных (base64, 12 байт). */
    @Column(name = "iv", nullable = false, columnDefinition = "TEXT")
    private String iv;

    /** Полезные данные, зашифрованные DEK (base64, без IV — IV в {@link #iv}). */
    @Column(name = "encrypted_data", nullable = false, columnDefinition = "TEXT")
    private String encryptedData;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;
}
